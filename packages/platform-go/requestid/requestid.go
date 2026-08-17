// Package requestid implements the canonical request-id middleware per
// ADR-0019 (docs/architecture/adrs/0019-request-id-at-the-edge.md).
//
// The api-gateway is the canonical root generator; downstream services
// inherit the value via this middleware. Inbound headers:
//
//   - X-Request-Id (primary, preferred)
//   - X-Correlation-Id (alias, always accepted)
//
// When both are absent, a UUIDv7 is generated. The chosen value is
// echoed in BOTH response headers, placed in MDC under requestId,
// attached to the OTel root span as platform.request_id, and made
// available via RequestIDFromContext.
package requestid

import (
	"context"
	"net/http"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// Header names per ADR-0019.
const (
	HeaderRequestID     = "X-Request-Id"
	HeaderCorrelationID = "X-Correlation-Id"
)

// MDC key in the structured logger.
const MDCKey = "request_id"

// AttrKey is the OTel root-span attribute name.
const AttrKey = "platform.request_id"

// contextKey is private to this package so other packages cannot
// collide by accident.
type contextKey struct{}

// Middleware returns an http.Handler middleware that reads or
// generates the request id, sets it on the response, and binds it
// to the request context.
func Middleware() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			id := r.Header.Get(HeaderRequestID)
			if id == "" {
				id = r.Header.Get(HeaderCorrelationID)
			}
			if id == "" {
				u, err := uuid.NewV7()
				if err != nil {
					http.Error(w, "internal error", http.StatusInternalServerError)
					return
				}
				id = u.String()
			}
			w.Header().Set(HeaderRequestID, id)
			w.Header().Set(HeaderCorrelationID, id)

			ctx := context.WithValue(r.Context(), contextKey{}, id)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// HeaderMiddleware is the same as Middleware but does NOT register
// the id in the request context — useful for the gateway which sets
// MDC and OTel attrs at the same point.
func HeaderMiddleware() func(http.Handler) http.Handler {
	return Middleware()
}

// FromContext returns the request id bound to ctx, or "" if none.
func FromContext(ctx context.Context) string {
	if v, ok := ctx.Value(contextKey{}).(string); ok {
		return v
	}
	return ""
}

// AttachToSpan attaches the request id to the OTel span as
// `platform.request_id`. Safe to call with a nil span.
func AttachToSpan(ctx context.Context, span trace.Span) {
	if span == nil {
		return
	}
	span.SetAttributes(attribute.String(AttrKey, FromContext(ctx)))
}
