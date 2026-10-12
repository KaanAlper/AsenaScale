//go:build linux

package tsbridge

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"syscall"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// phone is the "other apps" side of the TUN: a second netstack whose packets
// travel over a SOCK_SEQPACKET socketpair (one packet per read/write, like a
// TUN fd) into the engine under test.
type phone struct {
	st     *stack.Stack
	ep     *channel.Endpoint
	dev    io.ReadWriteCloser
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

var (
	phoneV4 = tcpip.AddrFrom4([4]byte{100, 64, 0, 1})
	peerV4  = tcpip.AddrFrom4([4]byte{100, 64, 0, 2})
	phoneV6 = tcpip.AddrFrom16(netip.MustParseAddr("fd7a:115c:a1e0::1").As16())
	peerV6  = tcpip.AddrFrom16(netip.MustParseAddr("fd7a:115c:a1e0::2").As16())
)

func newPhone(t *testing.T, dev io.ReadWriteCloser) *phone {
	t.Helper()
	st := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep := channel.New(256, 1280, "")
	if err := st.CreateNIC(1, ep); err != nil {
		t.Fatal(err)
	}
	for _, a := range []tcpip.ProtocolAddress{
		{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: phoneV4.WithPrefix()},
		{Protocol: ipv6.ProtocolNumber, AddressWithPrefix: phoneV6.WithPrefix()},
	} {
		if err := st.AddProtocolAddress(1, a, stack.AddressProperties{}); err != nil {
			t.Fatal(err)
		}
	}
	st.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
		{Destination: header.IPv6EmptySubnet, NIC: 1},
	})
	ctx, cancel := context.WithCancel(context.Background())
	p := &phone{st: st, ep: ep, dev: dev, cancel: cancel}
	p.wg.Add(2)
	go func() { // stack -> tun
		defer p.wg.Done()
		for {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			v := pkt.ToView()
			dev.Write(v.AsSlice())
			v.Release()
			pkt.DecRef()
		}
	}()
	go func() { // tun -> stack
		defer p.wg.Done()
		buf := make([]byte, 65535)
		for {
			n, err := dev.Read(buf)
			if err != nil {
				return
			}
			proto := header.IPv4ProtocolNumber
			if buf[0]>>4 == 6 {
				proto = header.IPv6ProtocolNumber
			}
			pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), buf[:n]...))})
			ep.InjectInbound(proto, pkt)
			pkt.DecRef()
		}
	}()
	t.Cleanup(func() {
		cancel()
		dev.Close()
		ep.Close()
		p.wg.Wait()
		st.Close()
		st.Wait()
	})
	return p
}

// fakeTailnet maps tailnet addresses to local test servers and records dials.
type fakeTailnet struct {
	mu     sync.Mutex
	routes map[string]string // "100.64.0.2:80" -> "127.0.0.1:1234"
	dials  []string
}

func (f *fakeTailnet) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	f.mu.Lock()
	f.dials = append(f.dials, network+" "+addr)
	to, ok := f.routes[network+" "+addr]
	f.mu.Unlock()
	if !ok {
		return nil, errors.New("connection refused")
	}
	var d net.Dialer
	return d.DialContext(ctx, network, to)
}

func (f *fakeTailnet) dialed() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.dials...)
}

func socketpair(t *testing.T) (io.ReadWriteCloser, io.ReadWriteCloser) {
	t.Helper()
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Fatal(err)
	}
	a, err := newFDDevice(fds[0])
	if err != nil {
		t.Fatal(err)
	}
	b, err := newFDDevice(fds[1])
	if err != nil {
		t.Fatal(err)
	}
	return a, b
}

func tcpEcho(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				io.Copy(c, c)
				c.(*net.TCPConn).CloseWrite()
				c.Close()
			}()
		}
	}()
	return ln.Addr().String()
}

func udpEcho(t *testing.T) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(append([]byte("echo:"), buf[:n]...), from)
		}
	}()
	return pc.LocalAddr().String()
}

func setup(t *testing.T, routes map[string]string, dns dnsFunc) (*phone, *engine, *fakeTailnet) {
	t.Helper()
	appSide, tunSide := socketpair(t)
	ft := &fakeTailnet{routes: routes}
	e, err := newEngine(tunSide, 1280, ft.dial, dns)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(e.close)
	return newPhone(t, appSide), e, ft
}

func TestTCPFlowIsDialedThroughTailnet(t *testing.T) {
	echo := tcpEcho(t)
	p, _, ft := setup(t, map[string]string{"tcp 100.64.0.2:8765": echo, "tcp [fd7a:115c:a1e0::2]:8765": echo}, nil)

	for _, tc := range []struct {
		name  string
		addr  tcpip.Address
		proto tcpip.NetworkProtocolNumber
	}{{"v4", peerV4, ipv4.ProtocolNumber}, {"v6", peerV6, ipv6.ProtocolNumber}} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			c, err := gonet.DialContextTCP(ctx, p.st, tcpip.FullAddress{NIC: 1, Addr: tc.addr, Port: 8765}, tc.proto)
			if err != nil {
				t.Fatalf("dial: %v", err)
			}
			defer c.Close()
			msg := bytes.Repeat([]byte("GET /healthz "), 2000) // several segments
			go func() {
				c.Write(msg)
				c.CloseWrite() // the half-close must reach the server
			}()
			c.SetReadDeadline(time.Now().Add(10 * time.Second))
			got, err := io.ReadAll(c)
			if err != nil {
				t.Fatalf("read: %v", err)
			}
			if !bytes.Equal(got, msg) {
				t.Fatalf("echo: got %d bytes, want %d", len(got), len(msg))
			}
		})
	}
	if d := ft.dialed(); len(d) != 2 || d[0] != "tcp 100.64.0.2:8765" || d[1] != "tcp [fd7a:115c:a1e0::2]:8765" {
		t.Fatalf("dials = %q", d)
	}
}

func TestTCPRefusedWhenTailnetRefuses(t *testing.T) {
	p, _, ft := setup(t, nil, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, err := gonet.DialContextTCP(ctx, p.st, tcpip.FullAddress{NIC: 1, Addr: peerV4, Port: 9}, ipv4.ProtocolNumber)
	if err == nil {
		t.Fatal("dial succeeded, want reset")
	}
	if ctx.Err() != nil {
		t.Fatal("dial timed out, want an immediate reset")
	}
	if d := ft.dialed(); len(d) != 1 {
		t.Fatalf("dials = %q", d)
	}
}

func TestNonTailnetDestinationNeverDialed(t *testing.T) {
	p, _, ft := setup(t, map[string]string{"tcp 8.8.8.8:80": "127.0.0.1:1"}, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, err := gonet.DialContextTCP(ctx, p.st, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{8, 8, 8, 8}), Port: 80}, ipv4.ProtocolNumber)
	if err == nil {
		t.Fatal("dial to 8.8.8.8 succeeded")
	}
	if d := ft.dialed(); len(d) != 0 {
		t.Fatalf("dials = %q, want none", d)
	}
}

func udpRoundTrip(t *testing.T, p *phone, to tcpip.Address, port uint16, msg string) string {
	t.Helper()
	c, err := gonet.DialUDP(p.st, nil, &tcpip.FullAddress{NIC: 1, Addr: to, Port: port}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	buf := make([]byte, 2048)
	for range 3 { // the first datagram may race the flow setup; retry like an app would
		if _, err := c.Write([]byte(msg)); err != nil {
			t.Fatal(err)
		}
		c.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := c.Read(buf)
		if err == nil {
			return string(buf[:n])
		}
	}
	t.Fatal("no UDP answer")
	return ""
}

func TestUDPFlowIsDialedThroughTailnet(t *testing.T) {
	echo := udpEcho(t)
	p, _, ft := setup(t, map[string]string{"udp 100.64.0.2:5353": echo}, nil)
	if got := udpRoundTrip(t, p, peerV4, 5353, "hello"); got != "echo:hello" {
		t.Fatalf("got %q", got)
	}
	if d := ft.dialed(); len(d) == 0 || d[0] != "udp 100.64.0.2:5353" {
		t.Fatalf("dials = %q", d)
	}
}

func TestQuad100DNSAnsweredLocally(t *testing.T) {
	var gotFrom netip.AddrPort
	var mu sync.Mutex
	dns := func(ctx context.Context, q []byte, from netip.AddrPort) ([]byte, error) {
		mu.Lock()
		gotFrom = from
		mu.Unlock()
		return append([]byte("answer:"), q...), nil
	}
	p, _, ft := setup(t, nil, dns)
	quad100 := tcpip.AddrFrom4([4]byte{100, 100, 100, 100})
	if got := udpRoundTrip(t, p, quad100, 53, "q"); got != "answer:q" {
		t.Fatalf("got %q", got)
	}
	if d := ft.dialed(); len(d) != 0 {
		t.Fatalf("DNS was dialed out: %q", d)
	}
	mu.Lock()
	defer mu.Unlock()
	if gotFrom.Addr() != netip.MustParseAddr("100.64.0.1") {
		t.Fatalf("query from %v", gotFrom)
	}
}

func TestCloseEndsFlowsAndDevice(t *testing.T) {
	echo := tcpEcho(t)
	p, e, _ := setup(t, map[string]string{"tcp 100.64.0.2:80": echo}, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, err := gonet.DialContextTCP(ctx, p.st, tcpip.FullAddress{NIC: 1, Addr: peerV4, Port: 80}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	done := make(chan struct{})
	go func() {
		e.close()
		e.close() // twice is fine
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("close hung")
	}
	if _, err := e.dev.Read(make([]byte, 10)); err == nil {
		t.Fatal("device still open after close")
	}
	e.mu.Lock()
	n := len(e.conns)
	e.mu.Unlock()
	if n != 0 {
		t.Fatalf("%d relays still tracked", n)
	}
}

func TestStopVPNWithoutStartIsSafe(t *testing.T) {
	StopVPN()
	if VPNActive() {
		t.Fatal("active without start")
	}
	if err := StartVPN(-1, 1280); err == nil {
		t.Fatal("bad fd accepted")
	}
	if VPNActive() {
		t.Fatal("active after failed start")
	}
}

func TestStartStopVPNOnFD(t *testing.T) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(fds[0])
	if err := StartVPN(fds[1], 0); err != nil {
		t.Fatal(err)
	}
	if !VPNActive() {
		t.Fatal("not active")
	}
	StopVPN()
	if VPNActive() {
		t.Fatal("still active")
	}
	// The engine closed its end: the other end sees EOF.
	syscall.SetNonblock(fds[0], false)
	n, err := syscall.Read(fds[0], make([]byte, 10))
	if n != 0 || err != nil {
		t.Fatalf("read after stop: n=%d err=%v, want EOF", n, err)
	}
}

func TestVPNInactiveAfterDeviceFails(t *testing.T) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Fatal(err)
	}
	if err := StartVPN(fds[1], 1280); err != nil {
		t.Fatal(err)
	}
	defer StopVPN()
	syscall.Close(fds[0]) // like Android tearing the interface down
	deadline := time.Now().Add(5 * time.Second)
	for VPNActive() {
		if time.Now().After(deadline) {
			t.Fatal("still active after the device failed")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
