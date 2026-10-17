package tsbridge

import (
	"io"
	"net"
	"testing"
	"time"
)

func listenLocal(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	return ln
}

func waitDone(t *testing.T, done <-chan struct{}, within time.Duration) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(within):
		t.Fatal("acceptOnce did not finish in time")
	}
}

func TestAcceptOnceTakesOneConnection(t *testing.T) {
	ln := listenLocal(t)
	addr := ln.Addr().String()
	handled := make(chan net.Conn, 2)
	done := make(chan struct{})
	go acceptOnce(ln, 5*time.Second, func(c net.Conn) { handled <- c }, func() { close(done) })

	c1, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer c1.Close()
	waitDone(t, done, time.Second)
	select {
	case c := <-handled:
		c.Close()
	case <-time.After(time.Second):
		t.Fatal("first connection was not handled")
	}
	if c2, err := net.DialTimeout("tcp", addr, time.Second); err == nil {
		c2.Close()
		t.Fatal("second dial worked; the listener should be closed")
	}
	if len(handled) != 0 {
		t.Fatal("a second connection was handled")
	}
}

func TestAcceptOnceClosesWhenIdle(t *testing.T) {
	ln := listenLocal(t)
	addr := ln.Addr().String()
	done := make(chan struct{})
	go acceptOnce(ln, 100*time.Millisecond, func(c net.Conn) {
		c.Close()
		t.Error("handle called without a connection")
	}, func() { close(done) })

	waitDone(t, done, time.Second)
	if c, err := net.DialTimeout("tcp", addr, time.Second); err == nil {
		c.Close()
		t.Fatal("dial worked after the idle timeout")
	}
}

func TestAcceptOnceHandsOverTheConnection(t *testing.T) {
	ln := listenLocal(t)
	got := make(chan string, 1)
	go acceptOnce(ln, 5*time.Second, func(c net.Conn) {
		defer c.Close()
		b, _ := io.ReadAll(c)
		got <- string(b)
	}, func() {})

	c, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	c.Close()
	select {
	case s := <-got:
		if s != "hello" {
			t.Fatalf("handle read %q, want %q", s, "hello")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("handle never read the bytes")
	}
}

func TestForwardOnceNeedsNode(t *testing.T) {
	if _, err := ForwardOnce("100.64.0.1:2222", time.Second); err == nil {
		t.Error("ForwardOnce worked without a node")
	}
}
