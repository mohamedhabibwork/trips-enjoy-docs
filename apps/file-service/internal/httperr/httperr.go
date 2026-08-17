// Package httperr holds the canonical platform error envelope
// (docs/architecture/DOWNSTREAM_ERROR_CATALOG.md §1) as exported
// types. It is imported by both internal/httpapi (which writes
// envelopes) and internal/files (which produces ValidationDetail
// values for the 400 response). Putting the types in a leaf package
// breaks the import cycle that would otherwise arise between files
// and httpapi.
//
// The file-service sentinel errors live here too for the same reason:
// router.go's mapServiceError compares against these values, and a
// shared leaf package keeps the dependency graph acyclic.
package httperr

import "errors"

// ErrorEnvelope is the canonical platform error response shape.
type ErrorEnvelope struct {
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

// File-service sentinel errors.
var (
	ErrFileNotFound       = errors.New("file not found")
	ErrFileNotAvailable   = errors.New("file not available")
	ErrFileTooLarge       = errors.New("file too large")
	ErrMimeTypeNotAllowed = errors.New("mime type not allowed")
	ErrSignatureInvalid   = errors.New("signature invalid")
	ErrLegalHoldActive    = errors.New("legal hold active")
)
