package internal_test

import (
	"testing"

	"github.com/trips-enjoy/platform-go/errormodel"
	"github.com/trips-enjoy/platform-go/requestid"
)

// TestChatServiceLinkToPlatformGo verifies the chat-service compiles
// against the shared Go library via the workspace substitution.
func TestChatServiceLinkToPlatformGo(t *testing.T) {
	_ = requestid.HeaderRequestID
	e := errormodel.New(errormodel.CodeValidationFailed, "bad", "/x", "t", "s")
	if e.Status != 400 {
		t.Errorf("status = %d", e.Status)
	}
}
