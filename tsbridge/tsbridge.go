// Package tsbridge embeds a userspace Tailscale node (tsnet) into the
// Android app. It is compiled with gomobile into an .aar and exposes a very
// small API to Kotlin: start the node, read its status, and open loopback
// TCP forwards to machines on the tailnet (used for SSH).
//
// No VpnService is needed: all tailnet traffic lives inside this process.
// Phone-wide mode (vpn.go) is the opt-in exception: other apps' tailnet
// traffic comes in through a TUN device and is dialed through this node.
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
	// userLog receives log lines on the desktop (the host app's log file).
	userLog atomic.Pointer[func(string)]
)

// SetLogger sends the bridge's log lines to f instead of stderr (desktop).
func SetLogger(f func(string)) {
	if f == nil {
		userLog.Store(nil)
		return
	}
	userLog.Store(&f)
}

func logf(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	if p := platform.Load(); p != nil {
		(*p).Log(line)
	} else if f := userLog.Load(); f != nil {
		(*f)(line)
	} else {
		log.Print(line)
	}
}

// Start boots the embedded Tailscale node. dataDir must be a private,
// writable directory. It returns immediately; poll Status for progress.
func Start(dataDir, hostname string, p Platform) error {
	platform.Store(&p)
	netmon.RegisterInterfaceGetter(getInterfaces)
	return start(dataDir, hostname, true)
}

// StartDesktop boots the node on Windows/Linux/macOS, where the OS lists
// network interfaces itself and the process environment must stay as is
// (the host app starts shells that inherit it).
func StartDesktop(dataDir, hostname string) error {
	return start(dataDir, hostname, false)
}

func start(dataDir, hostname string, mobile bool) error {
	startMu.Lock()
	defer startMu.Unlock()
	if current() != nil {
		return nil
	}

	dir := filepath.Join(dataDir, "tailscale")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if mobile {
		// tsnet looks for a home/config directory in a few places.
		os.Setenv("HOME", dataDir)
		os.Setenv("XDG_CONFIG_HOME", dataDir)
		// os.TempDir on Android is /data/local/tmp, which apps can't write.
		tmp := filepath.Join(dataDir, "tmp")
		os.MkdirAll(tmp, 0o700)
		os.Setenv("TMPDIR", tmp)
	}
	os.Setenv("TS_LOGS_DIR", dir)
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
	// Link details, for the devices view.
	LastSeen string `json:"lastSeen,omitempty"` // RFC 3339; empty while online
	Direct   string `json:"direct,omitempty"`   // ip:port when connected directly
	Relay    string `json:"relay,omitempty"`    // DERP region code when relayed
	RxBytes  int64  `json:"rx,omitempty"`
	TxBytes  int64  `json:"tx,omitempty"`
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
			Direct:  ps.CurAddr,
			Relay:   ps.Relay,
			RxBytes: ps.RxBytes,
			TxBytes: ps.TxBytes,
		}
		if !ps.Online && !ps.LastSeen.IsZero() {
			p.LastSeen = ps.LastSeen.UTC().Format(time.RFC3339)
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
	relay(local, remote)
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

// ForwardOnce is Forward for a single connection: the listener takes one
// connection, pipes it to target over the tailnet and closes; it also closes
// after idle without a connection. Only the PC's own processes reach
// 127.0.0.1, and only for that one connection, so the port is not a standing
// door to the peer. Returns the local port. CloseForward cancels an unused one.
func ForwardOnce(target string, idle time.Duration) (int, error) {
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
	// Stop may have run since current(): it already emptied forwards, so a
	// listener parked now would stay open for a node that is gone.
	if srv != s {
		mu.Unlock()
		ln.Close()
		return 0, errors.New("tailscale not started")
	}
	forwards[port] = ln
	mu.Unlock()

	go acceptOnce(ln, idle, func(c net.Conn) { pipe(s, c, target) }, func() {
		mu.Lock()
		if forwards[port] == ln {
			delete(forwards, port)
		}
		mu.Unlock()
	})
	return port, nil
}

// acceptOnce waits up to idle for one connection on ln, closes ln, then hands
// the connection (if any) to handle. done runs after ln is closed. Closing ln
// from elsewhere (CloseForward, Stop) ends the wait the same way.
func acceptOnce(ln net.Listener, idle time.Duration, handle func(net.Conn), done func()) {
	timer := time.AfterFunc(idle, func() { ln.Close() })
	c, err := ln.Accept()
	timer.Stop()
	ln.Close()
	done()
	if err != nil {
		return
	}
	handle(c)
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
	closeForwards()
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
