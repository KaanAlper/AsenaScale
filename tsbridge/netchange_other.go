//go:build !android

package tsbridge

// Only Android needs the app to report the default route.
func setDefaultRoute(ifname, gateway string) {}
