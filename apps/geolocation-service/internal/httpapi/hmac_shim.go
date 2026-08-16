package httpapi

import (
	"net/http"

	"github.com/trips-enjoy/platform/geolocation-service/internal/admin"
)

// adminVerifyHMACImpl forwards to the admin package's exported
// Verify function (kept package-private so the httpapi package does
// not need to know about the admin package's internals).
func adminVerifyHMACImpl(r *http.Request, secret []byte) bool {
	return admin.Verify(r, secret)
}
