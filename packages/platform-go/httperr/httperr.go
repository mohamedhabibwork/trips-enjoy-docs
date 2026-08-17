// Package httperr writes `errormodel.Envelope` responses with the
// correct Content-Type (`application/problem+json`) per RFC 7807.
package httperr

import (
	"net/http"

	"github.com/trips-enjoy/platform-go/errormodel"
)

// ContentType is the RFC 7807 media type.
const ContentType = "application/problem+json"

// Write writes the envelope as JSON with the problem+json content
// type. Status is taken from the envelope's own Status field.
func Write(w http.ResponseWriter, e errormodel.Envelope) {
	w.Header().Set("Content-Type", ContentType)
	w.WriteHeader(e.Status)
	_ = jsonEncode(w, e)
}

// WriteStatus writes the envelope with a custom HTTP status (overriding
// the envelope's Status field). Useful for cases where the HTTP status
// is determined by middleware (e.g. a recovery handler that wants
// to return 500 regardless of envelope content).
func WriteStatus(w http.ResponseWriter, status int, e errormodel.Envelope) {
	w.Header().Set("Content-Type", ContentType)
	w.WriteHeader(status)
	_ = jsonEncode(w, e)
}

// WriteProblem writes a `*errormodel.Envelope` from a pointer.
// Convenience for code paths that already have a pointer.
func WriteProblem(w http.ResponseWriter, e *errormodel.Envelope) {
	if e == nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	Write(w, *e)
}

// jsonEncode is a thin indirection so the rest of the package can
// avoid importing encoding/json directly (useful for testability).
var jsonEncode = func(w http.ResponseWriter, e errormodel.Envelope) error {
	enc := newJSONEncoder(w)
	return enc.Encode(e)
}
