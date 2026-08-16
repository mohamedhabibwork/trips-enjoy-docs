// Package apierr hosts the canonical error envelope shared between
// internal/auth (which writes 401/403 responses) and internal/httpapi
// (which writes every other error response). It exists as a standalone
// package to break the httpapi → auth → httpapi import cycle that
// otherwise forms when auth.Middleware uses WriteError.
//
// The envelope shape mirrors docs/architecture/DOWNSTREAM_ERROR_CATALOG.md
// §1 + API_STANDARDS.md: code + correlationId always populated;
// Details on 400 VALIDATION_FAILED; Downstream when the error
// originates in another service.
package apierr

import (
	"encoding/json"
	"log"
	"net/http"
)

// Envelope is the canonical platform error response shape.
type Envelope struct {
	Code          string             `json:"code"`
	Message       string             `json:"message"`
	CorrelationID string             `json:"correlationId"`
	Details       []ValidationDetail `json:"details,omitempty"`
	Downstream    *DownstreamBlock   `json:"downstream,omitempty"`
}

// ValidationDetail describes a single field-level validation failure.
type ValidationDetail struct {
	Field string `json:"field"`
	Issue string `json:"issue"`
}

// DownstreamBlock is attached when an error originates in another service.
type DownstreamBlock struct {
	Service   string `json:"service"`
	Code      string `json:"code"`
	Status    int    `json:"status"`
	TraceID   string `json:"traceId,omitempty"`
	LatencyMS int    `json:"latency_ms,omitempty"`
	Attempt   int    `json:"attempt,omitempty"`
}

// Write emits the canonical error envelope and emits a structured log
// line for observability. requestID is the correlation id already on
// the request (set by httpapi.RequestID or its equivalent).
func Write(w http.ResponseWriter, r *http.Request, status int, code, message, requestID string) {
	logError(r, status, code, message, requestID)
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(Envelope{
		Code:          code,
		Message:       message,
		CorrelationID: requestID,
	})
}

// WriteValidation emits the 400 VALIDATION_FAILED envelope with a
// Details array.
func WriteValidation(w http.ResponseWriter, r *http.Request, details []ValidationDetail, requestID string) {
	logError(r, http.StatusBadRequest, "VALIDATION_FAILED", "Request validation failed", requestID)
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(http.StatusBadRequest)
	_ = json.NewEncoder(w).Encode(Envelope{
		Code:          "VALIDATION_FAILED",
		Message:       "Request validation failed.",
		CorrelationID: requestID,
		Details:       details,
	})
}

// WriteDownstream emits an envelope with the Downstream block attached.
func WriteDownstream(w http.ResponseWriter, r *http.Request, status int, code, message string, ds *DownstreamBlock, requestID string) {
	logError(r, status, code, message, requestID)
	w.Header().Set("Content-Type", "application/problem+json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(Envelope{
		Code:          code,
		Message:       message,
		CorrelationID: requestID,
		Downstream:    ds,
	})
}

func logError(r *http.Request, status int, code, message, correlationID string) {
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
		Service:       "geolocation-service",
		CorrelationID: correlationID,
		Method:        methodOrEmpty(r),
		Path:          pathOrEmpty(r),
		Status:        status,
		Code:          code,
		Message:       message,
	})
	if err == nil {
		log.Print(string(entry))
	}
}

func methodOrEmpty(r *http.Request) string {
	if r == nil {
		return ""
	}
	return r.Method
}

func pathOrEmpty(r *http.Request) string {
	if r == nil {
		return ""
	}
	return r.URL.Path
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
