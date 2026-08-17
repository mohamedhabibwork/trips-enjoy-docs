// Package gateway — small helpers shared by router.go and friends.
//
// These are deliberately kept in their own file because router.go
// is the orchestrator and these helpers are the I/O primitives it
// leans on. Tests import them directly.
package gateway

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/go-chi/chi/v5"
)

// jsonMarshal is a thin shim for json.Marshal.
func jsonMarshal(v any) ([]byte, error) { return json.Marshal(v) }

// writeJSONBody writes v as a JSON body to w. Returns the marshal
// error so callers can decide whether to log it.
func writeJSONBody(w http.ResponseWriter, v any) error {
	return json.NewEncoder(w).Encode(v)
}

// copyAll is io.Copy without the need to import io at every call
// site.
func copyAll(dst io.Writer, src io.Reader) (int64, error) {
	return io.Copy(dst, src)
}

// chiRouteCtxKey extracts the chi router context key from an
// incoming request. chi stores URL params under
// context.Context[chi.RouteCtxKey]; we expose a tiny helper so the
// rest of the package needn't import chi internals.
func chiRouteCtxKey(r *http.Request) any {
	return chi.RouteCtxKey
}
