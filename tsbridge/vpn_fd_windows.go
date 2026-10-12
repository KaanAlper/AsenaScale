package tsbridge

import (
	"errors"
	"io"
)

// Phone-wide mode is Android only.
func newFDDevice(fd int) (io.ReadWriteCloser, error) {
	return nil, errors.New("phone-wide mode is not supported on Windows")
}
