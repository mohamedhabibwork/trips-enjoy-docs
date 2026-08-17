package internal_test

import (
	"testing"

	"github.com/trips-enjoy/platform-go/errormodel"
	"github.com/trips-enjoy/platform-go/money"
)

// TestFileServiceLinkToPlatformGo verifies the file-service compiles
// against the shared Go library via the workspace substitution.
func TestFileServiceLinkToPlatformGo(t *testing.T) {
	m := money.OfMinor(1234, "USD")
	if m.Major() != "12.34" {
		t.Errorf("major = %q", m.Major())
	}
	e := errormodel.New(errormodel.CodeConflict, "hash mismatch", "/files/abc", "t", "s")
	if e.Status != 409 {
		t.Errorf("status = %d", e.Status)
	}
}
