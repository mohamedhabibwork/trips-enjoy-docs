package httpapi

import (
	"encoding/json"
	"log"
	"net/http"

	"github.com/trips-enjoy/platform/file-service/internal/httperr"
)

// The canonical envelope types live in internal/httperr (a leaf
// package) so files.Service and httpapi do not form an import cycle.
// Re-export them here so existing callers keep working unchanged.
type (
	ErrorEnvelope    = httperr.ErrorEnvelope
	ValidationDetail = httperr.ValidationDetail
	DownstreamBlock  = httperr.DownstreamBlock
)

// WriteError emits the canonical error envelope, sets Content-Type to
// application/problem+json (RFC 7807) per SHARED_CONVENTIONS.md, and
// emits a structured log line for observability.
func WriteError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	logError(r, status, code, message)
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(ErrorEnvelope{
		Code:          code,
		Message:       message,
		CorrelationID: RequestIDFromContext(r.Context()),
	})
}

// WriteValidationError is the 400 response used when JSON Schema
// validation fails; populates the Details array.
func WriteValidationError(w http.ResponseWriter, r *http.Request, details []ValidationDetail) {
	logError(r, http.StatusBadRequest, "VALIDATION_FAILED", "Request validation failed")
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(http.StatusBadRequest)
	_ = json.NewEncoder(w).Encode(ErrorEnvelope{
		Code:          "VALIDATION_FAILED",
		Message:       "Request validation failed.",
		CorrelationID: RequestIDFromContext(r.Context()),
		Details:       details,
	})
}

// WriteDownstreamError attaches a Downstream block describing the
// original failure (when this service propagated an error from a
// CRITICAL downstream).
func WriteDownstreamError(w http.ResponseWriter, r *http.Request, status int, code, message string, ds *DownstreamBlock) {
	logError(r, status, code, message)
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(ErrorEnvelope{
		Code:          code,
		Message:       message,
		CorrelationID: RequestIDFromContext(r.Context()),
		Downstream:    ds,
	})
}

func logError(r *http.Request, status int, code, message string) {
	entry, err := json.Marshal(struct {
		Level         string `json:"level"`
		Service       string `json:"service"`
		CorrelationID string `json:"correlation_id"`
		Method        string `json:"method"`
		Path          string `json:"path"`
		Status        int    `json:"status"`
		Code          string `json:"code"`
		Message       string `json:"message"`
	}{
		Level:         "error",
		Service:       "file-service",
		CorrelationID: RequestIDFromContext(r.Context()),
		Method:        r.Method,
		Path:          r.URL.Path,
		Status:        status,
		Code:          code,
		Message:       message,
	})
	if err == nil {
		log.Print(string(entry))
	}
}

// CanonicalCode is the HTTP-status → error-code mapping per
// DOWNSTREAM_ERROR_CATALOG.md §1. Exported so handlers can use the
// shared mapping without re-declaring the strings everywhere.
func CanonicalCode(status int) string {
	switch status {
	case http.StatusBadRequest:
		return "VALIDATION_FAILED"
	case http.StatusUnauthorized:
		return "UNAUTHENTICATED"
	case http.StatusForbidden:
		return "FORBIDDEN"
	case http.StatusNotFound:
		return "NOT_FOUND"
	case http.StatusConflict:
		return "STATE_INVALID"
	case http.StatusUnprocessableEntity:
		return "BUSINESS_RULE_VIOLATION"
	case http.StatusTooManyRequests:
		return "RATE_LIMITED"
	case http.StatusBadGateway:
		return "BAD_GATEWAY"
	case http.StatusGatewayTimeout:
		return "DEPENDENCY_TIMEOUT"
	case http.StatusServiceUnavailable:
		return "DEPENDENCY_UNAVAILABLE"
	default:
		return "INTERNAL_ERROR"
	}
}
