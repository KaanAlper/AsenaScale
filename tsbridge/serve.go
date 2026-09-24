package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

// Desktop side: the PC app's SSH server listens on 127.0.0.1 only, and
// Serve feeds it the connections that arrive on this node's tailnet port.
// Peer tells it who is on the other end of each one.

var (
	peersMu sync.Mutex
	// loopback source port of a forwarded connection -> tailnet address of the peer
	peers = map[int]string{}
)

// Serve accepts TCP connections on tailnetPort of this node and pipes each
// to 127.0.0.1:localPort. It returns once listening.
func Serve(tailnetPort, localPort int) error {
	s := current()
	if s == nil {
		return errors.New("tailscale not started")
	}
	ln, err := s.Listen("tcp", fmt.Sprintf(":%d", tailnetPort))
	if err != nil {
		return err
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				logf("serve: %v", err)
				return
			}
			go forwardIn(c, localPort)
		}
	}()
	return nil
}

func forwardIn(remote net.Conn, localPort int) {
	defer remote.Close()
	local, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", localPort), 5*time.Second)
	if err != nil {
		logf("serve: local dial: %v", err)
		return
	}
	defer local.Close()
	if tc, ok := local.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	srcPort := local.LocalAddr().(*net.TCPAddr).Port
	peersMu.Lock()
	peers[srcPort] = remote.RemoteAddr().String()
	peersMu.Unlock()
	defer func() {
		peersMu.Lock()
		delete(peers, srcPort)
		peersMu.Unlock()
	}()
	done := make(chan struct{}, 2)
	go func() { io.Copy(local, remote); done <- struct{}{} }()
	go func() { io.Copy(remote, local); done <- struct{}{} }()
	<-done
}

type peerInfo struct {
	IP   string `json:"ip"`
	Name string `json:"name"`
	User string `json:"user"`
	OS   string `json:"os"`
}

// Peer describes the tailnet device behind a forwarded connection, given the
// connection's source port as seen by the local server. It returns "" for
// connections that didn't come through Serve (i.e. local processes).
func Peer(srcPort int) string {
	peersMu.Lock()
	addr, ok := peers[srcPort]
	peersMu.Unlock()
	if !ok {
		return ""
	}
	info := peerInfo{IP: addr}
	if host, _, err := net.SplitHostPort(addr); err == nil {
		info.IP = host
	}
	if s := current(); s != nil {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if who, err := lc.WhoIs(ctx, addr); err == nil {
				if who.Node != nil {
					info.Name = strings.TrimSuffix(who.Node.ComputedName, ".")
					if who.Node.Hostinfo.Valid() {
						info.OS = who.Node.Hostinfo.OS()
					}
				}
				if who.UserProfile != nil {
					info.User = who.UserProfile.DisplayName
				}
			}
		}
	}
	b, _ := json.Marshal(info)
	return string(b)
}
