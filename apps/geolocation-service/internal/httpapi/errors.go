package httpapi

import (
	"net/http"

	"github.com/trips-enjoy/platform/geolocation-service/internal/apierr"
)

// ErrorEnvelope is the canonical platform error response shape —
// re-exported from internal/apierr for callers that want to decode it
// without an extra import. The shape mirrors
// docs/architecture/DOWNSTREAM_ERROR_CATALOG.md §1 + API_STANDARDS.md.
type ErrorEnvelope = apierr.Envelope

// ValidationDetail describes a single field-level validation failure.
type ValidationDetail = apierr.ValidationDetail

// DownstreamBlock is attached when an error originates in another
// service (per DOWNSTREAM_ERROR_CATALOG.md §5).
type DownstreamBlock = apierr.DownstreamBlock

// WriteError emits the canonical error envelope, sets Content-Type to
// application/problem+json (RFC 7807) per SHARED_CONVENTIONS.md, and
// emits a structured log line for observability.
func WriteError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	apierr.Write(w, r, status, code, message, RequestIDFromContext(r.Context()))
}

// WriteValidationError is the 400 response used when JSON Schema
// validation fails; populates the Details array.
func WriteValidationError(w http.ResponseWriter, r *http.Request, details []ValidationDetail) {
	apierr.WriteValidation(w, r, details, RequestIDFromContext(r.Context()))
}

// WriteDownstreamError attaches a Downstream block describing the
// original failure (when this service propagated an error from a
// CRITICAL downstream).
func WriteDownstreamError(w http.ResponseWriter, r *http.Request, status int, code, message string, ds *DownstreamBlock) {
	apierr.WriteDownstream(w, r, status, code, message, ds, RequestIDFromContext(r.Context()))
}

// CanonicalCode is the HTTP-status → error-code mapping per
// DOWNSTREAM_ERROR_CATALOG.md §1. Re-exported from apierr so handlers
// can use the shared mapping without importing the apierr package.
func CanonicalCode(status int) string { return apierr.CanonicalCode(status) }
