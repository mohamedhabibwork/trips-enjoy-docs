// Package observability owns the structured JSON logger + the OTel SDK
// init. The logger satisfies docs/architecture/OBSERVABILITY.md ("Log
// fields"): timestamp, level, service, correlation_id, trace_id, span_id,
// message. OTel init is best-effort: when no OTLP endpoint is configured
// the SDK falls back to a no-op so the binary still boots offline.
package observability

import (
	"context"
	"log/slog"
	"os"
	"sync"
)

// logKey is the unexported context key used by WithRequestID to attach
// the request id to slog handlers. We avoid the slog.Default() pattern
// because some tests want a fresh logger per case.
type logKey struct{}

// Logger is the structured JSON logger used everywhere in the service.
// All log lines go to stdout (per PLATFORM_BASELINE.md §Observability)
// with the platform-required fields.
type Logger struct {
	base *slog.Logger
}

// NewLogger returns a Logger tagged with service + env + region.
func NewLogger(service, env, region string) *Logger {
	base := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})).With(
		slog.String("service", service),
		slog.String("env", env),
		slog.String("region", region),
	)
	return &Logger{base: base}
}

// WithRequestID returns a context tagged with the request id so downstream
// log calls include it automatically. Use FromContext to retrieve.
func WithRequestID(ctx context.Context, requestID, traceID, spanID string) context.Context {
	return context.WithValue(ctx, logKey{}, entry{requestID: requestID, traceID: traceID, spanID: spanID})
}

// FromContext returns a slog logger pre-populated with the request's
// correlation ids (if any). Returns the base logger when the context has
// no ids — safe to call unconditionally.
func (l *Logger) FromContext(ctx context.Context) *slog.Logger {
	v, _ := ctx.Value(logKey{}).(entry)
	if v.requestID == "" && v.traceID == "" {
		return l.base
	}
	attrs := make([]any, 0, 3)
	if v.requestID != "" {
		attrs = append(attrs, slog.String("correlation_id", v.requestID), slog.String("request_id", v.requestID))
	}
	if v.traceID != "" {
		attrs = append(attrs, slog.String("trace_id", v.traceID))
	}
	if v.spanID != "" {
		attrs = append(attrs, slog.String("span_id", v.spanID))
	}
	return l.base.With(attrs...)
}

type entry struct {
	requestID string
	traceID   string
	spanID    string
}

// Nop is a no-op logger used when observability.NewLogger is not wired
// (e.g. in tests that don't care about log output).
var Nop = NewLogger("geolocation-service", "test", "local")

var once sync.Once
