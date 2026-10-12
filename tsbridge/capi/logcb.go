package main

/*
#include <stdlib.h>
typedef void (*as_log_fn)(const char*);
static inline void as_call_log(void* f, const char* s) { ((as_log_fn)f)(s); }
*/
import "C"

import (
	"unsafe"

	"github.com/kaanalper/asenascale/tsbridge"
)

func setLogger(f unsafe.Pointer) {
	if f == nil {
		tsbridge.SetLogger(nil)
		return
	}
	tsbridge.SetLogger(func(line string) {
		cs := C.CString(line)
		C.as_call_log(f, cs)
		C.free(unsafe.Pointer(cs))
	})
}
