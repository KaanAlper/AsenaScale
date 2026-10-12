package tsbridge

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
)

var (
	vpnMu sync.Mutex
	vpn   *engine
)

// StartVPN serves phone-wide mode on fd, a TUN device from Android's
// VpnService that routes only tailnet ranges. It takes ownership of fd
// (closed by StopVPN, or right away on error). Flows are dialed through
// the embedded node, so Start must have been called; a flow that arrives
// while the node is down is refused, not queued.
func StartVPN(fd int, mtu int) error {
	if mtu < 576 || mtu > 65535 {
		mtu = 1280
	}
	dev, err := newFDDevice(fd)
	if err != nil {
		return err
	}
	vpnMu.Lock()
	defer vpnMu.Unlock()
	if vpn != nil {
		vpn.close()
		vpn = nil
	}
	e, err := newEngine(dev, uint32(mtu), tailnetDial, tailnetDNS)
	if err != nil {
		return err
	}
	vpn = e
	logf("phone-wide: on (mtu %d)", mtu)
	return nil
}

// StopVPN stops phone-wide mode and closes its TUN device. The embedded
// node and the app's own forwards (SSH) are not touched.
func StopVPN() {
	vpnMu.Lock()
	e := vpn
	vpn = nil
	vpnMu.Unlock()
	if e != nil {
		e.close()
		logf("phone-wide: off")
	}
}

// VPNActive reports whether phone-wide mode is serving a TUN device. It
// turns false by itself if the device failed (the app then re-establishes).
func VPNActive() bool {
	vpnMu.Lock()
	defer vpnMu.Unlock()
	return vpn != nil && vpn.alive()
}

func tailnetDial(ctx context.Context, network, addr string) (net.Conn, error) {
	s := current()
	if s == nil {
		return nil, errors.New("tailscale not started")
	}
	return s.Dial(ctx, network, addr)
}

func tailnetDNS(ctx context.Context, query []byte, from netip.AddrPort) ([]byte, error) {
	s := current()
	if s == nil || s.Sys() == nil {
		return nil, errors.New("tailscale not started")
	}
	dm, ok := s.Sys().DNSManager.GetOK()
	if !ok {
		return nil, errors.New("no resolver yet")
	}
	return dm.Query(ctx, query, "udp", from)
}
