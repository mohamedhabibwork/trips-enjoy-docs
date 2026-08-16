package files

import (
	"encoding/json"

	"github.com/trips-enjoy/platform/file-service/internal/httperr"
)

// buildValidationEnvelope is a tiny helper that constructs the canonical
// platform envelope JSON for a 400 VALIDATION_FAILED response. Lives in
// this package because validation.go (which calls it) must not import
// internal/httpapi (import cycle).
func buildValidationEnvelope(details []httperr.ValidationDetail) []byte {
	body, _ := json.Marshal(httperr.ErrorEnvelope{
		Code:    "VALIDATION_FAILED",
		Message: "Request validation failed.",
		Details: details,
	})
	return body
}
