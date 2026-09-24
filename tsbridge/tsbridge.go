// Package tsbridge embeds a userspace Tailscale node (tsnet) into the
// Android app. It is compiled with gomobile into an .aar and exposes a very
// small API to Kotlin: start the node, read its status, and open loopback
// TCP forwards to machines on the tailnet (used for SSH).
//
// No VpnService is needed: all tailnet traffic lives inside this process.
package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	_ "golang.org/x/mobile/bind"
	"tailscale.com/envknob"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

// Platform is implemented on the Kotlin side.
type Platform interface {
	// InterfacesJSON returns the device network interfaces as JSON. Android
	// 11+ blocks netlink, so Go cannot enumerate interfaces by itself.
	InterfacesJSON() string
	// Log receives log lines from the Go side.
	Log(line string)
}

var (
	// startMu serializes Start/Stop. It is held while tsnet boots, so
	// nothing tsnet calls back into (getInterfaces, logf) may take it.
	startMu sync.Mutex

	mu       sync.Mutex // guards srv and forwards; never held across tsnet calls
	srv      *tsnet.Server
	forwards = map[int]net.Listener{}

	platform atomic.Pointer[Platform]
)

func logf(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	if p := platform.Load(); p != nil {
		(*p).Log(line)
	} else {
		log.Print(line)
	}
}

// Start boots the embedded Tailscale node. dataDir must be a private,
// writable directory. It returns immediately; poll Status for progress.
func Start(dataDir, hostname string, p Platform) error {
	startMu.Lock()
	defer startMu.Unlock()
	if current() != nil {
		return nil
	}
	platform.Store(&p)
	netmon.RegisterInterfaceGetter(getInterfaces)

	dir := filepath.Join(dataDir, "tailscale")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// tsnet looks for a home/config directory in a few places.
	os.Setenv("HOME", dataDir)
	os.Setenv("XDG_CONFIG_HOME", dataDir)
	os.Setenv("TS_LOGS_DIR", dir)
	// os.TempDir on Android is /data/local/tmp, which apps can't write.
	tmp := filepath.Join(dataDir, "tmp")
	os.MkdirAll(tmp, 0o700)
	os.Setenv("TMPDIR", tmp)
	// Don't stream debug logs to Tailscale's servers: saves battery and data.
	envknob.SetNoLogsNoSupport()

	s := &tsnet.Server{
		Dir:      dir,
		Hostname: hostname,
		UserLogf: func(format string, args ...any) { logf(format, args...) },
		Logf:     func(string, ...any) {},
	}
	if err := s.Start(); err != nil {
		return err
	}
	mu.Lock()
	srv = s
	mu.Unlock()
	return nil
}

type peerJSON struct {
	Name    string   `json:"name"`
	DNSName string   `json:"dnsName"`
	OS      string   `json:"os"`
	IPs     []string `json:"ips"`
	Online  bool     `json:"online"`
}

type statusJSON struct {
	State   string     `json:"state"`
	AuthURL string     `json:"authURL"`
	Self    *peerJSON  `json:"self,omitempty"`
	Tailnet string     `json:"tailnet"`
	Peers   []peerJSON `json:"peers"`
	Error   string     `json:"error,omitempty"`
}

func current() *tsnet.Server {
	mu.Lock()
	defer mu.Unlock()
	return srv
}

// Status returns the node state as JSON.
func Status() string {
	out := statusJSON{State: "Stopped", Peers: []peerJSON{}}
	s := current()
	if s == nil {
		return mustJSON(out)
	}
	lc, err := s.LocalClient()
	if err != nil {
		out.Error = err.Error()
		return mustJSON(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		out.State = "Starting"
		out.Error = err.Error()
		return mustJSON(out)
	}
	out.State = st.BackendState
	out.AuthURL = st.AuthURL
	if st.CurrentTailnet != nil {
		out.Tailnet = st.CurrentTailnet.Name
	}
	if st.Self != nil {
		self := peerJSON{
			Name:    st.Self.HostName,
			DNSName: strings.TrimSuffix(st.Self.DNSName, "."),
			OS:      st.Self.OS,
			Online:  true,
		}
		for _, ip := range st.Self.TailscaleIPs {
			self.IPs = append(self.IPs, ip.String())
		}
		out.Self = &self
	}
	for _, ps := range st.Peer {
		p := peerJSON{
			Name:    ps.HostName,
			DNSName: strings.TrimSuffix(ps.DNSName, "."),
			OS:      ps.OS,
			Online:  ps.Online,
		}
		for _, ip := range ps.TailscaleIPs {
			p.IPs = append(p.IPs, ip.String())
		}
		out.Peers = append(out.Peers, p)
	}
	sort.Slice(out.Peers, func(i, j int) bool {
		if out.Peers[i].Online != out.Peers[j].Online {
			return out.Peers[i].Online
		}
		return out.Peers[i].Name < out.Peers[j].Name
	})
	return mustJSON(out)
}

// Login starts an interactive login; the URL shows up in Status().authURL.
func Login() error {
	s := current()
	if s == nil {
		return errors.New("tailscale not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// Logout logs the node out of the tailnet.
func Logout() error {
	s := current()
	if s == nil {
		return nil
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

// Forward listens on a random 127.0.0.1 port and pipes every accepted
// connection to target ("host:port") over the tailnet. It returns the local
// port. Close it with CloseForward.
func Forward(target string) (int, error) {
	s := current()
	if s == nil {
		return 0, errors.New("tailscale not started")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	mu.Lock()
	forwards[port] = ln
	mu.Unlock()

	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go pipe(s, c, target)
		}
	}()
	return port, nil
}

func pipe(s *tsnet.Server, local net.Conn, target string) {
	defer local.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	remote, err := s.Dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		logf("dial %s: %v", target, err)
		return
	}
	defer remote.Close()
	if tc, ok := local.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, local); done <- struct{}{} }()
	go func() { io.Copy(local, remote); done <- struct{}{} }()
	<-done
}

// CloseForward stops a forward created by Forward.
func CloseForward(port int) {
	mu.Lock()
	ln := forwards[port]
	delete(forwards, port)
	mu.Unlock()
	if ln != nil {
		ln.Close()
	}
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"state":"Error","error":` + fmt.Sprintf("%q", err.Error()) + `}`
	}
	return string(b)
}

// Stop disconnects from the tailnet and shuts the embedded node down. The
// login is kept on disk, so a later Start reconnects without a new login.
func Stop() {
	startMu.Lock()
	defer startMu.Unlock()
	mu.Lock()
	s := srv
	srv = nil
	fwd := forwards
	forwards = map[int]net.Listener{}
	mu.Unlock()
	for _, ln := range fwd {
		ln.Close()
	}
	if s != nil {
		s.Close()
	}
}

// NetworkChanged is called by Kotlin when Android's default network changes
// (Wi-Fi <-> mobile data, network lost/regained). Without it, netmon on
// Android only re-checks every 10 minutes. ifname is "" when offline.
func NetworkChanged(ifname, gateway string) {
	setDefaultRoute(ifname, gateway)
	s := current()
	if s == nil || s.Sys() == nil {
		return
	}
	if nm, ok := s.Sys().NetMon.GetOK(); ok {
		nm.InjectEvent()
	}
}

// Probe dials target over the tailnet and hangs up. It returns "" when the
// port answers, or the dial error, so the app can say *why* it can't
// connect (nothing listening vs. firewall/asleep) instead of a bare EOF.
func Probe(target string) string {
	s := current()
	if s == nil {
		return "tailscale not started"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, err := s.Dial(ctx, "tcp", target)
	if err != nil {
		return err.Error()
	}
	c.Close()
	return ""
}

// Ping sends a Tailscale-level ping (TSMP: answered by the peer's tailscaled,
// not its OS) to a peer IP. It returns the round trip in milliseconds, or -1.
// Used to tell "the tunnel can't reach the PC" apart from "the PC's firewall
// or SSH server doesn't answer".
func Ping(ip string) int {
	s := current()
	if s == nil {
		return -1
	}
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return -1
	}
	lc, err := s.LocalClient()
	if err != nil {
		return -1
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	res, err := lc.Ping(ctx, addr, tailcfg.PingTSMP)
	if err != nil || res.Err != "" {
		return -1
	}
	return int(res.LatencySeconds*1000 + 0.5)
}

// Banner connects to target over the tailnet and returns the first line
// the server sends (an SSH server's identification, e.g.
// "SSH-2.0-AsenaScale_0.1.0"), or "" if nothing answers. The app uses
// it to recognize the PC companion app.
func Banner(target string) string {
	s := current()
	if s == nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	c, err := s.Dial(ctx, "tcp", target)
	if err != nil {
		return ""
	}
	defer c.Close()
	c.SetReadDeadline(time.Now().Add(4 * time.Second))
	buf := make([]byte, 128)
	n, _ := io.ReadAtLeast(c, buf, 1)
	line := string(buf[:n])
	if i := strings.IndexAny(line, "\r\n"); i >= 0 {
		line = line[:i]
	}
	return line
}
