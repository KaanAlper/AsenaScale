package tsbridge

import (
	"encoding/json"
	"net"
	"net/netip"
	"strings"

	"tailscale.com/net/netmon"
)

type addrJSON struct {
	IP        string `json:"ip"`
	PrefixLen int    `json:"prefixLen"`
}

type ifaceJSON struct {
	Name         string     `json:"name"`
	Index        int        `json:"index"`
	MTU          int        `json:"mtu"`
	Up           bool       `json:"up"`
	Broadcast    bool       `json:"broadcast"`
	Loopback     bool       `json:"loopback"`
	PointToPoint bool       `json:"pointToPoint"`
	Multicast    bool       `json:"multicast"`
	Addrs        []addrJSON `json:"addrs"`
}

// getInterfaces asks Kotlin for the interface list (java.net.NetworkInterface
// still works on Android 11+, unlike netlink from Go).
func getInterfaces() ([]netmon.Interface, error) {
	mu.Lock()
	p := platform
	mu.Unlock()
	if p == nil {
		return nil, nil
	}
	raw := strings.TrimSpace(p.InterfacesJSON())
	if raw == "" {
		return nil, nil
	}
	var in []ifaceJSON
	if err := json.Unmarshal([]byte(raw), &in); err != nil {
		return nil, err
	}
	out := make([]netmon.Interface, 0, len(in))
	for _, it := range in {
		if it.Name == "" {
			continue
		}
		nif := netmon.Interface{
			Interface: &net.Interface{Name: it.Name, Index: it.Index, MTU: it.MTU},
			AltAddrs:  []net.Addr{},
		}
		if it.Up {
			nif.Flags |= net.FlagUp
		}
		if it.Broadcast {
			nif.Flags |= net.FlagBroadcast
		}
		if it.Loopback {
			nif.Flags |= net.FlagLoopback
		}
		if it.PointToPoint {
			nif.Flags |= net.FlagPointToPoint
		}
		if it.Multicast {
			nif.Flags |= net.FlagMulticast
		}
		for _, a := range it.Addrs {
			ip, err := netip.ParseAddr(a.IP)
			if err != nil {
				continue
			}
			if ip.Zone() != "" {
				nif.AltAddrs = append(nif.AltAddrs, &net.IPAddr{IP: ip.AsSlice(), Zone: ip.Zone()})
				continue
			}
			bits := ip.BitLen()
			if a.PrefixLen < 0 || a.PrefixLen > bits {
				nif.AltAddrs = append(nif.AltAddrs, &net.IPAddr{IP: ip.AsSlice()})
				continue
			}
			nif.AltAddrs = append(nif.AltAddrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(a.PrefixLen, bits)})
		}
		out = append(out, nif)
	}
	return out, nil
}
