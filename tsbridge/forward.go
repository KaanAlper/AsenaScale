package tsbridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/tailcfg"
)

// Desktop side: ports the user chose (asenascale forward add PORT) are
// opened on this node's tailnet address and piped to 127.0.0.1:PORT, so a
// phone in phone-wide mode can reach a server on the PC even when the PC's
// own Tailscale is off. Only devices of the same Tailscale user get through.

var (
	fwdMu  sync.Mutex
	fwdLns = map[int]net.Listener{} // tailnet port -> listener
)

// parsePorts reads "8765,3000" (spaces allowed, "" = none) into sorted,
// de-duplicated ports.
func parsePorts(csv string) ([]int, error) {
	seen := map[int]bool{}
	var out []int
	for _, f := range strings.Split(csv, ",") {
		f = strings.TrimSpace(f)
		if f == "" {
			continue
		}
		p, err := strconv.Atoi(f)
		if err != nil || p < 1 || p > 65535 {
			return nil, fmt.Errorf("bad port %q", f)
		}
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	sort.Ints(out)
	return out, nil
}

// SetForwards makes exactly the ports in csv ("8765,3000"; "" for none)
// reachable on this node, each piped to the same port on 127.0.0.1. Ports
// already open stay open; ones no longer listed are closed. It needs a
// started node; Stop closes them all.
func SetForwards(csv string) error {
	want, err := parsePorts(csv)
	if err != nil {
		return err
	}
	s := current()
	if s == nil {
		return errors.New("tailscale not started")
	}
	wanted := map[int]bool{}
	for _, p := range want {
		wanted[p] = true
	}
	fwdMu.Lock()
	defer fwdMu.Unlock()
	// Stop may have run since current(): never park listeners of a closed
	// node in fwdLns (a later SetForwards would skip those ports).
	if current() != s {
		return errors.New("tailscale not started")
	}
	for p, ln := range fwdLns {
		if !wanted[p] {
			ln.Close()
			delete(fwdLns, p)
			logf("forward: closed port %d", p)
		}
	}
	var errs []error
	for _, p := range want {
		if fwdLns[p] != nil {
			continue
		}
		ln, err := s.Listen("tcp", fmt.Sprintf(":%d", p))
		if err != nil {
			errs = append(errs, fmt.Errorf("port %d: %w", p, err))
			continue
		}
		fwdLns[p] = ln
		logf("forward: tailnet port %d -> 127.0.0.1:%d", p, p)
		go acceptForward(ln, p)
	}
	return errors.Join(errs...)
}

func closeForwards() {
	fwdMu.Lock()
	lns := fwdLns
	fwdLns = map[int]net.Listener{}
	fwdMu.Unlock()
	for _, ln := range lns {
		ln.Close()
	}
}

// maxForwardConns bounds the open connections per forwarded port, so a
// flood can't pile up goroutines and local sockets.
const maxForwardConns = 64

func acceptForward(ln net.Listener, port int) {
	slots := make(chan struct{}, maxForwardConns)
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		select {
		case slots <- struct{}{}:
		default:
			logf("forward: refused %s -> port %d (too many connections)", c.RemoteAddr(), port)
			c.Close()
			continue
		}
		go func() {
			defer func() { <-slots }()
			who, ok := forwardAllowed(c.RemoteAddr().String())
			if !ok {
				logf("forward: refused %s -> port %d (%s)", c.RemoteAddr(), port, who)
				c.Close()
				return
			}
			logf("forward: %s -> 127.0.0.1:%d", who, port)
			forwardIn(c, port)
		}()
	}
}

// samePerson is the forward policy: the peer must belong to the same
// Tailscale user as this node (a device shared in from another account, or
// a tagged server, is refused).
func samePerson(self, peer tailcfg.UserID) bool {
	return self != 0 && self == peer
}

// forwardAllowed looks up who is behind remote; it returns a label for the
// log and whether the connection may go through.
func forwardAllowed(remote string) (string, bool) {
	s := current()
	if s == nil {
		return remote, false
	}
	lc, err := s.LocalClient()
	if err != nil {
		return remote, false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil || st.Self == nil {
		return remote + ": no status", false
	}
	who, err := lc.WhoIs(ctx, remote)
	if err != nil || who.Node == nil {
		return remote + ": unknown device", false
	}
	label := strings.TrimSuffix(who.Node.ComputedName, ".") + " " + remote
	var peerUser tailcfg.UserID
	if who.UserProfile != nil {
		peerUser = who.UserProfile.ID
		label += " (" + who.UserProfile.LoginName + ")"
	}
	if who.Node.IsTagged() {
		return label + ": tagged device", false
	}
	if !samePerson(st.Self.UserID, peerUser) {
		return label + ": another user", false
	}
	return label, true
}
