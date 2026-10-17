// Command capi builds tsbridge as a C static library for the AsenaScale
// desktop app (Rust links it; see host/build.rs).
package main

// #include <stdlib.h>
import "C"

import (
	"unsafe"

	"github.com/kaanalper/asenascale/tsbridge"
)

func cstr(s string) *C.char { return C.CString(s) }

func cerr(err error) *C.char {
	if err == nil {
		return nil
	}
	return C.CString(err.Error())
}

//export AsStart
func AsStart(dataDir, hostname *C.char) *C.char {
	return cerr(tsbridge.StartDesktop(C.GoString(dataDir), C.GoString(hostname)))
}

//export AsServe
func AsServe(tailnetPort, localPort C.int) *C.char {
	return cerr(tsbridge.Serve(int(tailnetPort), int(localPort)))
}

// AsSetForwards opens exactly these tailnet ports ("8765,3000"; "" = none),
// each piped to the same port on 127.0.0.1.
//
//export AsSetForwards
func AsSetForwards(ports *C.char) *C.char {
	return cerr(tsbridge.SetForwards(C.GoString(ports)))
}

// AsForward listens on a random 127.0.0.1 port and pipes each connection to
// target ("100.x.y.z:2222") over the tailnet. It writes the port to *port and
// returns nil, or returns the error text.
//
//export AsForward
func AsForward(target *C.char, port *C.int) *C.char {
	p, err := tsbridge.Forward(C.GoString(target))
	if err != nil {
		return cerr(err)
	}
	*port = C.int(p)
	return nil
}

// AsCloseForward stops a forward made by AsForward.
//
//export AsCloseForward
func AsCloseForward(port C.int) { tsbridge.CloseForward(int(port)) }

// AsSetLogger routes the bridge's log lines to f, a C function taking a
// NUL-terminated string that is only valid during the call.
//
//export AsSetLogger
func AsSetLogger(f unsafe.Pointer) { setLogger(f) }

//export AsStatus
func AsStatus() *C.char { return cstr(tsbridge.Status()) }

//export AsPeer
func AsPeer(srcPort C.int) *C.char { return cstr(tsbridge.Peer(int(srcPort))) }

//export AsLogin
func AsLogin() *C.char { return cerr(tsbridge.Login()) }

//export AsLogout
func AsLogout() *C.char { return cerr(tsbridge.Logout()) }

//export AsPing
func AsPing(ip *C.char) C.int { return C.int(tsbridge.Ping(C.GoString(ip))) }

//export AsStop
func AsStop() { tsbridge.Stop() }

//export AsFree
func AsFree(p *C.char) { C.free(unsafe.Pointer(p)) }

func main() {}
