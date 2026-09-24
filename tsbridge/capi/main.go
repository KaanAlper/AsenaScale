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
