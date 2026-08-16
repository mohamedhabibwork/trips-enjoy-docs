package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"time"
)

// requestIDContextKey is unexported so callers must use
// RequestIDFromContext instead of poking the context directly.
type requestIDContextKey struct{}

// RequestID is a chi middleware that resolves the request id from
// inbound headers (X-Request-Id first, X-Correlation-Id as alias per
// ADR-0019) and falls back to a freshly generated UUIDv7 (per
// docs/shared/CONVENTIONS.md §2). It then sets BOTH headers on the
// response so the value round-trips to the caller.
func RequestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestID := r.Header.Get("X-Request-Id")
		if requestID == "" {
			requestID = r.Header.Get("X-Correlation-Id")
		}
		if requestID == "" {
			requestID = newUUIDv7()
		}

		w.Header().Set("X-Request-Id", requestID)
		w.Header().Set("X-Correlation-Id", requestID)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), requestIDContextKey{}, requestID)))
	})
}

// RequestIDFromContext extracts the request id stored by RequestID.
// Returns an empty string if the middleware never ran (only possible
// in tests).
func RequestIDFromContext(ctx context.Context) string {
	value, _ := ctx.Value(requestIDContextKey{}).(string)
	return value
}

// newUUIDv7 generates a UUIDv7-shaped string from the current unix-ms
// timestamp plus 10 random bytes. It does not require the google/uuid
// dependency at the httpapi layer (so this file stays a drop-in for
// the gateway), but the value still parses as a valid UUIDv7.
func newUUIDv7() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		panic("unable to generate request id")
	}

	milliseconds := uint64(time.Now().UnixMilli())
	value[0] = byte(milliseconds >> 40)
	value[1] = byte(milliseconds >> 32)
	value[2] = byte(milliseconds >> 24)
	value[3] = byte(milliseconds >> 16)
	value[4] = byte(milliseconds >> 8)
	value[5] = byte(milliseconds)
	value[6] = (value[6] & 0x0f) | 0x70
	value[8] = (value[8] & 0x3f) | 0x80

	encoded := make([]byte, 36)
	hex.Encode(encoded[0:8], value[0:4])
	encoded[8] = '-'
	hex.Encode(encoded[9:13], value[4:6])
	encoded[13] = '-'
	hex.Encode(encoded[14:18], value[6:8])
	encoded[18] = '-'
	hex.Encode(encoded[19:23], value[8:10])
	encoded[23] = '-'
	hex.Encode(encoded[24:36], value[10:16])
	return string(encoded)
}
