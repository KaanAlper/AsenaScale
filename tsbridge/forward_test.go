package tsbridge

import (
	"net/netip"
	"reflect"
	"testing"

	"tailscale.com/tailcfg"
)

func TestParsePorts(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want []int
		bad  bool
	}{
		{"", nil, false},
		{" ", nil, false},
		{"8765", []int{8765}, false},
		{"8765, 3000,8765,", []int{3000, 8765}, false},
		{"0", nil, true},
		{"65536", nil, true},
		{"80;81", nil, true},
		{"-1", nil, true},
	} {
		got, err := parsePorts(tc.in)
		if (err != nil) != tc.bad {
			t.Errorf("parsePorts(%q) err = %v, bad = %v", tc.in, err, tc.bad)
			continue
		}
		if !tc.bad && !reflect.DeepEqual(got, tc.want) {
			t.Errorf("parsePorts(%q) = %v, want %v", tc.in, got, tc.want)
		}
	}
}

func TestSamePerson(t *testing.T) {
	if !samePerson(tailcfg.UserID(7), tailcfg.UserID(7)) {
		t.Error("same user refused")
	}
	if samePerson(tailcfg.UserID(7), tailcfg.UserID(8)) {
		t.Error("other user allowed")
	}
	if samePerson(0, 0) {
		t.Error("unknown users allowed")
	}
}

func TestSetForwardsNeedsNode(t *testing.T) {
	if err := SetForwards("8765"); err == nil {
		t.Error("SetForwards worked without a node")
	}
	if err := SetForwards("nope"); err == nil {
		t.Error("bad port list accepted")
	}
}

func TestFlowAllowed(t *testing.T) {
	for addr, want := range map[string]bool{
		"100.64.0.1":           true,
		"100.127.255.254":      true,
		"100.100.100.100":      true,
		"fd7a:115c:a1e0::1":    true,
		"::ffff:100.64.0.9":    true,
		"100.128.0.1":          false,
		"8.8.8.8":              false,
		"192.168.1.1":          false,
		"fd7a:115c:a1e1::1":    false,
		"2001:4860:4860::8888": false,
	} {
		if got := flowAllowed(netip.MustParseAddr(addr)); got != want {
			t.Errorf("flowAllowed(%s) = %v, want %v", addr, got, want)
		}
	}
	if !isQuad100DNS(netip.MustParseAddrPort("100.100.100.100:53")) || isQuad100DNS(netip.MustParseAddrPort("100.100.100.100:80")) {
		t.Error("quad-100 DNS detection")
	}
}
