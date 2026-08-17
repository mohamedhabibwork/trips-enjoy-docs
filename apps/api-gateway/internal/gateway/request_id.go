// Package gateway — request-id filter (canonical contract).
//
// Implements docs/architecture/adrs/0019-request-id-at-the-edge.md.
// The gateway is the canonical root of the platform's per-request
// id; `X-Request-Id` and `X-Correlation-Id` are aliases. The contract:
//
//  1. Inbound header — read X-Request-Id; if absent read
//     X-Correlation-Id; if both absent generate a UUIDv7. If both
//     are sent, the value of X-Request-Id wins.
//  2. Response — set BOTH X-Request-Id AND X-Correlation-Id to the
//     same value.
//  3. Outbound HTTP — the reverse-proxy adds both as outbound
//     headers on every downstream call (see proxy.go).
//  4. Outbound Kafka — the Kafka producer adds both as Kafka headers
//     on every emitted event (see kafka_producer.go).
//  5. Audit event — the correlation_id field equals the request id.
//  6. MDC — every JSON log line in the request scope carries the
//     value under request_id (and correlation_id for back-compat).
//  7. OpenTelemetry — root-span attribute platform.request_id holds
//     the value; the OTel trace_id (W3C traceparent) is distinct.
//  8. Retry — the id is stable across retries; if the client sends
//     the same X-Request-Id the gateway does not regenerate.
//
// This file contains the middleware and the context helpers used by
// the proxy, the Kafka producer, the structured logger, and the
// OTel binding.
package gateway

import (
	"context"
	"net/http"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// contextKey types avoid cross-package key collisions.
type (
	requestIDContextKey struct{}
)

// RequestID extracts the request id (canonical root generator) from
// the request context. Returns "" if none is bound.
func RequestID(ctx context.Context) string {
	v, _ := ctx.Value(requestIDContextKey{}).(string)
	return v
}

// RequestIDFromContext returns the request id from r's context,
// matching the existing helper used by metrics / errors / proxy.
func RequestIDFromContext(ctx context.Context) string { return RequestID(ctx) }

// RequestIDMiddleware runs FIRST in the chain (before JWT, rate-limit,
// any other filter). Per ADR-0019 step 8 the id is stable across
// retries, so the middleware does NOT regenerate when an inbound
// value is present.
func RequestIDMiddleware() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			id := r.Header.Get("X-Request-Id")
			if id == "" {
				id = r.Header.Get("X-Correlation-Id")
			}
			if id == "" {
				u, err := uuid.NewV7()
				if err != nil {
					http.Error(w, "internal error", http.StatusInternalServerError)
					return
				}
				id = u.String()
			}
			w.Header().Set("X-Request-Id", id)
			w.Header().Set("X-Correlation-Id", id)

			// Bind to MDC for the structured logger.
			ctx := WithLogFields(r.Context(), logFields{
				CorrelationID: id,
				RequestID:     id,
			})
			// Bind to context for downstream code.
			ctx = context.WithValue(ctx, requestIDContextKey{}, id)

			// Bind to OTel root span (per ADR-0019 step 7).
			span := trace.SpanFromContext(ctx)
			span.SetAttributes(attribute.String("platform.request_id", id))

			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// PropagateOutbound copies the request id from ctx into the outbound
// HTTP headers on req. Called by the reverse-proxy.
func PropagateOutbound(ctx context.Context, req *http.Request) {
	id := RequestID(ctx)
	if id == "" {
		return
	}
	req.Header.Set("X-Request-Id", id)
	req.Header.Set("X-Correlation-Id", id)
}

// SetLogField mutates the MDC fields on ctx. Use this from the
// proxy once the user identity is known (claim-to-header
// translation), and from the proxy once status / latency are
// available, so every log line carries the right fields.
func SetLogField(ctx context.Context, mutate func(*logFields)) context.Context {
	cur := LogFieldsFromContext(ctx)
	mutate(&cur)
	return WithLogFields(ctx, cur)
}
