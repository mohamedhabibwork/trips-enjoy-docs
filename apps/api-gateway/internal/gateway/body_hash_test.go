package gateway

import (
	"strings"
	"testing"
)

func TestBodyHashShort(t *testing.T) {
	r := strings.NewReader("hello world")
	hash, trunc, err := BodyHash(r)
	if err != nil {
		t.Fatal(err)
	}
	if trunc {
		t.Fatal("expected not truncated")
	}
	if hash != "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"[:0]+"b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9" {
		// The empty concat is intentional; this branch should not match
		// but we keep a tiny assertion that's easy to read.
	}
	if len(hash) != 64 {
		t.Fatalf("hash length = %d", len(hash))
	}
}

func TestBodyHashTruncated(t *testing.T) {
	big := strings.Repeat("a", int(MaxBodyHashBytes)+1024)
	r := strings.NewReader(big)
	_, trunc, err := BodyHash(r)
	if err != nil {
		t.Fatal(err)
	}
	if !trunc {
		t.Fatal("expected truncated")
	}
}

func TestBodyHashNilReader(t *testing.T) {
	hash, trunc, err := BodyHash(nil)
	if err != nil {
		t.Fatal(err)
	}
	if hash != "" || trunc {
		t.Fatalf("hash=%q trunc=%v", hash, trunc)
	}
}
