//go:build !windows

package tsbridge

import (
	"fmt"
	"io"
	"os"
	"syscall"
)

// newFDDevice wraps a TUN file descriptor. Non-blocking mode puts it on Go's
// poller: reads park without a thread or busy loop, and Close interrupts them.
func newFDDevice(fd int) (io.ReadWriteCloser, error) {
	if fd < 0 {
		return nil, fmt.Errorf("bad tun fd %d", fd)
	}
	if err := syscall.SetNonblock(fd, true); err != nil {
		syscall.Close(fd)
		return nil, fmt.Errorf("tun fd: %w", err)
	}
	return os.NewFile(uintptr(fd), "tun"), nil
}
