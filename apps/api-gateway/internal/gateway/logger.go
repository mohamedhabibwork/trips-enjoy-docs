// Package gateway — structured logger.
//
// The gateway emits one JSON object per log line via the stdlib
// `log/slog`. The canonical field set follows
// docs/architecture/OBSERVABILITY.md and docs/services/api-gateway/README.md §15:
//
//	{ts, level, service, version, env, region,
//	 correlation_id, request_id, trace_id,
//	 user_id, user_type, route, method,
//	 status, latency_ms,
//	 upstream, upstream_status, upstream_latency_ms,
//	 client_ip, user_agent, msg}
//
// Per-request fields are stashed on the context via WithLogger (the
// Go-equivalent of SLF4J's MDC) so every log line in the request
// scope carries them automatically. Sensitive fields (JWT, body,
// PAN) MUST NOT be added to the logger.
package gateway

import (
	"context"
	"io"
	"log/slog"
	"os"
	"time"
)

// Logger fields stashed on the context (MDC equivalent).
type logFields struct {
	CorrelationID   string `json:"correlation_id"`
	RequestID       string `json:"request_id"`
	TraceID         string `json:"trace_id"`
	UserID          string `json:"user_id"`
	UserType        string `json:"user_type"`
	Route           string `json:"route"`
	Method          string `json:"method"`
	Status          int    `json:"status"`
	LatencyMs       int64  `json:"latency_ms"`
	Upstream        string `json:"upstream"`
	UpstreamStatus  int    `json:"upstream_status"`
	UpstreamLatency int64  `json:"upstream_latency_ms"`
	ClientIP        string `json:"client_ip"`
	UserAgent       string `json:"user_agent"`
}

type logContextKey struct{}

// WithLogFields binds the given per-request fields to ctx. Values
// passed in are shallow-copied; later mutations are not observed by
// downstream readers.
func WithLogFields(ctx context.Context, f logFields) context.Context {
	if f.Route == "" {
		f.Route = "unknown"
	}
	if f.Method == "" {
		f.Method = "UNKNOWN"
	}
	return context.WithValue(ctx, logContextKey{}, f)
}

// LogFieldsFromContext returns the per-request log fields bound to
// ctx, or the zero value if none are bound.
func LogFieldsFromContext(ctx context.Context) logFields {
	f, _ := ctx.Value(logContextKey{}).(logFields)
	return f
}

// NewLogger creates the platform-standard JSON logger. Output goes
// to w (or stdout if w is nil). Service metadata is attached as
// permanent attributes.
func NewLogger(w io.Writer, service, version, env, region string) *slog.Logger {
	if w == nil {
		w = os.Stdout
	}
	h := slog.NewJSONHandler(w, &slog.HandlerOptions{
		Level:       slog.LevelInfo,
		ReplaceAttr: timestampRFC3339,
	})
	l := slog.New(h).With(
		slog.String("service", service),
		slog.String("version", version),
		slog.String("env", env),
		slog.String("region", region),
	)
	return l
}

// LogFromContext returns a slog.Logger pre-populated with the
// per-request fields bound to ctx. If no fields are bound, returns
// the fallback logger.
func LogFromContext(ctx context.Context, fallback *slog.Logger) *slog.Logger {
	f := LogFieldsFromContext(ctx)
	if (f == logFields{}) || fallback == nil {
		return fallback
	}
	attrs := []any{
		slog.String("correlation_id", f.CorrelationID),
		slog.String("request_id", f.RequestID),
		slog.String("route", f.Route),
		slog.String("method", f.Method),
	}
	if f.TraceID != "" {
		attrs = append(attrs, slog.String("trace_id", f.TraceID))
	}
	if f.UserID != "" {
		attrs = append(attrs, slog.String("user_id", f.UserID))
	}
	if f.UserType != "" {
		attrs = append(attrs, slog.String("user_type", f.UserType))
	}
	if f.Status != 0 {
		attrs = append(attrs, slog.Int("status", f.Status))
	}
	if f.LatencyMs > 0 {
		attrs = append(attrs, slog.Int64("latency_ms", f.LatencyMs))
	}
	if f.Upstream != "" {
		attrs = append(attrs, slog.String("upstream", f.Upstream))
	}
	if f.UpstreamStatus != 0 {
		attrs = append(attrs, slog.Int("upstream_status", f.UpstreamStatus))
	}
	if f.UpstreamLatency > 0 {
		attrs = append(attrs, slog.Int64("upstream_latency_ms", f.UpstreamLatency))
	}
	if f.ClientIP != "" {
		attrs = append(attrs, slog.String("client_ip", f.ClientIP))
	}
	if f.UserAgent != "" {
		attrs = append(attrs, slog.String("user_agent", f.UserAgent))
	}
	return fallback.With(attrs...)
}

func timestampRFC3339(groups []string, a slog.Attr) slog.Attr {
	switch a.Key {
	case slog.TimeKey:
		return slog.String("ts", time.Now().UTC().Format(time.RFC3339Nano))
	case slog.MessageKey:
		return slog.String("msg", a.Value.String())
	case slog.LevelKey:
		return slog.String("level", a.Value.String())
	}
	return a
}
