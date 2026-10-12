package tsbridge

import (
	"io"
	"net"
	"testing"
	"time"
)

// A client that sends its request and then shuts its write side (curl with
// --http1.0, nc on a closed stdin) must still get the whole reply.
func TestRelayPassesHalfClose(t *testing.T) {
	server, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() {
		c, err := server.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		req, _ := io.ReadAll(c) // until the client's half-close arrives
		time.Sleep(50 * time.Millisecond)
		c.Write(append([]byte("reply to "), req...))
	}()

	front, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer front.Close()
	go func() {
		in, err := front.Accept()
		if err != nil {
			return
		}
		out, err := net.Dial("tcp", server.Addr().String())
		if err != nil {
			in.Close()
			return
		}
		relay(in, out)
		in.Close()
		out.Close()
	}()

	c, err := net.Dial("tcp", front.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	c.Write([]byte("ping"))
	c.(*net.TCPConn).CloseWrite()
	got, err := io.ReadAll(c)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "reply to ping" {
		t.Fatalf("got %q", got)
	}
}
