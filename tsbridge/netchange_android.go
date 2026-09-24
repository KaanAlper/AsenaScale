package tsbridge

import "tailscale.com/net/netmon"

func setDefaultRoute(ifname, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifname)
	if gateway != "" {
		netmon.UpdateLastKnownDefaultGateway(gateway)
	}
}
