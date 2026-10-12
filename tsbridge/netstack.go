package tsbridge

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
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
	"gvisor.dev/gvisor/pkg/waiter"
	"tailscale.com/net/tsaddr"
)

// Phone-wide mode: Android hands us a TUN device that carries only tailnet
// destinations (100.64.0.0/10, fd7a:115c:a1e0::/48). A userspace TCP/IP
// stack (gVisor netstack) terminates every TCP and UDP flow coming out of
// it, and each flow is dialed again through the embedded tsnet node.

type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// dnsFunc answers one raw DNS query sent by from (the app's address).
type dnsFunc func(ctx context.Context, query []byte, from netip.AddrPort) ([]byte, error)

const (
	nicID          = 1
	tcpDialTimeout = 15 * time.Second
	// UDP flows have no end marker: they are dropped after this much silence.
	udpIdleTimeout = 2 * time.Minute
	dnsIdleTimeout = 15 * time.Second
	maxPacket      = 65535
)

// flowAllowed reports whether a flow to dst may go over the tailnet: only
// Tailscale addresses, so a bad route can never leak other traffic into it.
func flowAllowed(dst netip.Addr) bool {
	dst = dst.Unmap()
	return tsaddr.CGNATRange().Contains(dst) || tsaddr.TailscaleULARange().Contains(dst)
}

// isQuad100DNS reports whether dst is Tailscale's built-in resolver.
func isQuad100DNS(dst netip.AddrPort) bool {
	a := dst.Addr().Unmap()
	return dst.Port() == 53 && (a == tsaddr.TailscaleServiceIP() || a == tsaddr.TailscaleServiceIPv6())
}

type engine struct {
	dev  io.ReadWriteCloser
	st   *stack.Stack
	ep   *channel.Endpoint
	dial dialFunc
	dns  dnsFunc // nil: DNS to 100.100.100.100 is dialed like any other flow

	ctx    context.Context
	cancel context.CancelFunc
	loops  sync.WaitGroup // the two packet pumps

	mu     sync.Mutex
	conns  map[io.Closer]struct{} // closed on shutdown so relays end
	closed bool
	once   sync.Once
}

// newEngine starts a netstack on dev (one IP packet per Read/Write, no
// header, like a Linux/Android TUN device). It owns dev from here on.
func newEngine(dev io.ReadWriteCloser, mtu uint32, dial dialFunc, dns dnsFunc) (*engine, error) {
	st := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	sack := tcpip.TCPSACKEnabled(true)
	st.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	ep := channel.New(512, mtu, "")
	fail := func(err error) (*engine, error) {
		st.Close()
		dev.Close()
		return nil, err
	}
	if err := st.CreateNIC(nicID, ep); err != nil {
		return fail(errors.New(err.String()))
	}
	// Accept packets for any destination and answer as that destination.
	if err := st.SetPromiscuousMode(nicID, true); err != nil {
		return fail(errors.New(err.String()))
	}
	if err := st.SetSpoofing(nicID, true); err != nil {
		return fail(errors.New(err.String()))
	}
	st.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	ctx, cancel := context.WithCancel(context.Background())
	e := &engine{dev: dev, st: st, ep: ep, dial: dial, dns: dns, ctx: ctx, cancel: cancel, conns: map[io.Closer]struct{}{}}
	tcpFwd := tcp.NewForwarder(st, 0, 256, e.handleTCP)
	st.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(st, e.handleUDP)
	st.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	e.loops.Add(2)
	go e.readLoop()
	go e.writeLoop()
	return e, nil
}

// readLoop moves packets from the device into the stack. It blocks in Read
// (the device is pollable), so an idle tunnel costs no CPU.
func (e *engine) readLoop() {
	defer e.loops.Done()
	buf := make([]byte, maxPacket)
	for {
		n, err := e.dev.Read(buf)
		if err != nil {
			if e.ctx.Err() == nil {
				logf("phone-wide: tun read: %v", err)
				go e.close()
			}
			return
		}
		if n == 0 {
			continue
		}
		var proto tcpip.NetworkProtocolNumber
		switch buf[0] >> 4 {
		case 4:
			proto = header.IPv4ProtocolNumber
		case 6:
			proto = header.IPv6ProtocolNumber
		default:
			continue
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(append([]byte(nil), buf[:n]...)),
		})
		e.ep.InjectInbound(proto, pkt)
		pkt.DecRef()
	}
}

// writeLoop moves the stack's outgoing packets to the device.
func (e *engine) writeLoop() {
	defer e.loops.Done()
	for {
		pkt := e.ep.ReadContext(e.ctx)
		if pkt == nil {
			return
		}
		v := pkt.ToView()
		_, err := e.dev.Write(v.AsSlice())
		v.Release()
		pkt.DecRef()
		if err != nil && e.ctx.Err() == nil {
			logf("phone-wide: tun write: %v", err)
		}
	}
}

// track registers conns to be closed on shutdown; false (nothing
// registered) if already shut down.
func (e *engine) track(conns ...io.Closer) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return false
	}
	for _, c := range conns {
		e.conns[c] = struct{}{}
	}
	return true
}

func (e *engine) untrack(conns ...io.Closer) {
	e.mu.Lock()
	for _, c := range conns {
		delete(e.conns, c)
	}
	e.mu.Unlock()
}

func addrPort(a tcpip.Address, port uint16) netip.AddrPort {
	ip, _ := netip.AddrFromSlice(a.AsSlice())
	return netip.AddrPortFrom(ip.Unmap(), port)
}

func (e *engine) handleTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := addrPort(id.LocalAddress, id.LocalPort)
	if !flowAllowed(dst.Addr()) || e.ctx.Err() != nil {
		r.Complete(true)
		return
	}
	// Dial first: if the tailnet side refuses, the app gets a reset
	// instead of a connection that dies right after the handshake.
	ctx, cancel := context.WithTimeout(e.ctx, tcpDialTimeout)
	remote, err := e.dial(ctx, "tcp", dst.String())
	cancel()
	if err != nil {
		logf("phone-wide: tcp %v: %v", dst, err)
		r.Complete(true)
		return
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		remote.Close()
		r.Complete(true)
		return
	}
	r.Complete(false)
	ep.SocketOptions().SetKeepAlive(true)
	local := gonet.NewTCPConn(&wq, ep)
	go e.relayTCP(local, remote)
}

type closeWriter interface{ CloseWrite() error }

// relayTCP copies both ways, passing a half-close on, until both sides are done.
func (e *engine) relayTCP(a, b net.Conn) {
	if !e.track(a, b) {
		a.Close()
		b.Close()
		return
	}
	defer func() {
		a.Close()
		b.Close()
		e.untrack(a, b)
	}()
	var wg sync.WaitGroup
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		_, err := io.Copy(dst, src)
		if cw, ok := dst.(closeWriter); ok && err == nil {
			cw.CloseWrite()
			return
		}
		dst.Close()
		src.Close()
	}
	wg.Add(2)
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
}

// handleUDP runs on the stack's packet path, so it must not block: the
// endpoint is created here (which delivers the first datagram) and the
// tailnet side is dialed in the background.
func (e *engine) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := addrPort(id.LocalAddress, id.LocalPort)
	src := addrPort(id.RemoteAddress, id.RemotePort)
	if !flowAllowed(dst.Addr()) || e.ctx.Err() != nil {
		return true // dropped
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		return true
	}
	local := gonet.NewUDPConn(&wq, ep)
	go func() {
		if e.dns != nil && isQuad100DNS(dst) {
			e.serveDNS(local, src)
			return
		}
		ctx, cancel := context.WithTimeout(e.ctx, tcpDialTimeout)
		remote, err := e.dial(ctx, "udp", dst.String())
		cancel()
		if err != nil {
			logf("phone-wide: udp %v: %v", dst, err)
			local.Close()
			return
		}
		e.relayUDP(local, remote, udpIdleTimeout)
	}()
	return true
}

// relayUDP copies datagrams both ways until the flow is idle for idle.
func (e *engine) relayUDP(a, b net.Conn, idle time.Duration) {
	if !e.track(a, b) {
		a.Close()
		b.Close()
		return
	}
	defer func() {
		a.Close()
		b.Close()
		e.untrack(a, b)
	}()
	var last atomic.Int64 // unix nanos of the last datagram either way
	last.Store(time.Now().UnixNano())
	var wg sync.WaitGroup
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		defer dst.Close()
		defer src.Close()
		buf := make([]byte, maxPacket)
		for {
			src.SetReadDeadline(time.Now().Add(idle))
			n, err := src.Read(buf)
			if err != nil {
				var ne net.Error
				if errors.As(err, &ne) && ne.Timeout() && time.Since(time.Unix(0, last.Load())) < idle {
					continue // the other direction is still busy
				}
				return
			}
			last.Store(time.Now().UnixNano())
			if _, err := dst.Write(buf[:n]); err != nil {
				return
			}
		}
	}
	wg.Add(2)
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
}

// serveDNS answers queries sent to 100.100.100.100:53 with the node's own
// resolver (MagicDNS names and the tailnet's DNS settings).
func (e *engine) serveDNS(c net.Conn, from netip.AddrPort) {
	if !e.track(c) {
		c.Close()
		return
	}
	defer func() {
		c.Close()
		e.untrack(c)
	}()
	buf := make([]byte, maxPacket)
	for {
		c.SetReadDeadline(time.Now().Add(dnsIdleTimeout))
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		ctx, cancel := context.WithTimeout(e.ctx, 5*time.Second)
		resp, err := e.dns(ctx, append([]byte(nil), buf[:n]...), from)
		cancel()
		if err != nil || len(resp) == 0 {
			continue
		}
		if _, err := c.Write(resp); err != nil {
			return
		}
	}
}

// alive reports whether the engine still serves its device (it shuts
// itself down when the device fails, e.g. Android closed the interface).
func (e *engine) alive() bool { return e.ctx.Err() == nil }

// close stops the stack, every flow and the device. Safe to call twice.
func (e *engine) close() {
	e.once.Do(func() {
		e.cancel()
		e.mu.Lock()
		e.closed = true
		conns := e.conns
		e.conns = map[io.Closer]struct{}{}
		e.mu.Unlock()
		for c := range conns {
			c.Close()
		}
		e.dev.Close() // unblocks readLoop
		e.ep.Close()
		e.loops.Wait()
		e.st.Close()
		e.st.Wait()
	})
}
