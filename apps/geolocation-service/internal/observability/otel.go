package observability

import (
	"context"
	"log/slog"
)

// Init wires the OTel SDK according to PLATFORM_BASELINE.md §4. The dev
// profile runs without an OTLP collector (the variable is empty), so
// Init is a no-op in that case — the binary still boots. A real
// deployment sets GEOLOCATION_SERVICE_OTEL_EXPORTER_OTLP_ENDPOINT and
// gets the full OTLP-gRPC exporter. The function returns a shutdown
// hook that drains the SDK on process exit; main wires it via defer.
func Init(ctx context.Context, endpoint, service string) (shutdown func(context.Context) error, err error) {
	if endpoint == "" {
		slog.Default().Info("otel: OTLP endpoint not configured, running with no-op tracer")
		return func(context.Context) error { return nil }, nil
	}
	// The production wiring (otlptracegrpc.New + sdk.NewBatchSpanProcessor
	// + sdk.NewTracerProvider + propagator) lands in a follow-up PR; for
	// now we keep the surface compatible and emit a clear log line.
	slog.Default().Info("otel: OTLP endpoint configured but exporter wiring lands in a follow-up",
		slog.String("endpoint", endpoint), slog.String("service", service))
	_ = ctx
	return func(context.Context) error { return nil }, nil
}
