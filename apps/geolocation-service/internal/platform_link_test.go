package internal_test

import (
	"testing"

	"github.com/trips-enjoy/platform-go/errormodel"
	"github.com/trips-enjoy/platform-go/requestid"
)

// TestGeolocationServiceLinkToPlatformGo verifies the geolocation-service
// compiles against the shared Go library via the workspace substitution.
func TestGeolocationServiceLinkToPlatformGo(t *testing.T) {
	_ = requestid.HeaderCorrelationID
	e := errormodel.New(errormodel.CodeNotFound, "no driver", "/drivers/near", "t", "s")
	if e.Status != 404 {
		t.Errorf("status = %d", e.Status)
	}
}
