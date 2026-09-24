// Command tstry exercises the bridge on a desktop: start, poll status, stop.
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"time"

	"github.com/kaanalper/asenascale/tsbridge"
)

type plat struct{}

// InterfacesJSON mimics the Kotlin side (java.net.NetworkInterface).
func (plat) InterfacesJSON() string {
	ifs, _ := net.Interfaces()
	var out []map[string]any
	for _, i := range ifs {
		var addrs []map[string]any
		as, _ := i.Addrs()
		for _, a := range as {
			if n, ok := a.(*net.IPNet); ok {
				ones, _ := n.Mask.Size()
				addrs = append(addrs, map[string]any{"ip": n.IP.String(), "prefixLen": ones})
			}
		}
		out = append(out, map[string]any{
			"name": i.Name, "index": i.Index, "mtu": i.MTU,
			"up": i.Flags&net.FlagUp != 0, "loopback": i.Flags&net.FlagLoopback != 0,
			"multicast": i.Flags&net.FlagMulticast != 0, "broadcast": i.Flags&net.FlagBroadcast != 0,
			"addrs": addrs,
		})
	}
	b, _ := json.Marshal(out)
	return string(b)
}
func (plat) Log(line string)        { fmt.Println("[ts]", line) }

func main() {
	dir, _ := os.MkdirTemp("", "tstry")
	defer os.RemoveAll(dir)
	if err := tsbridge.Start(dir, "tstry", plat{}); err != nil {
		panic(err)
	}
	for i := 0; i < 3; i++ {
		st := tsbridge.Status()
		fmt.Println(st)
		if len(st) > 0 && (contains(st, `"authURL":"https`)) {
			break
		}
		time.Sleep(time.Second)
	}
	tsbridge.NetworkChanged("eth0", "")
	p, err := tsbridge.Forward("100.100.100.100:53")
	fmt.Println("forward port", p, err)
	tsbridge.CloseForward(p)
	tsbridge.Stop()
	fmt.Println("after stop:", tsbridge.Status())
	// Toggle back on, as the app's switch does.
	if err := tsbridge.Start(dir, "tstry", plat{}); err != nil {
		panic(err)
	}
	time.Sleep(2 * time.Second)
	fmt.Println("restarted:", tsbridge.Status())
	tsbridge.Stop()
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
