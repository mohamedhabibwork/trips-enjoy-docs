// Package gateway — OpenTelemetry initialization.
//
// The gateway is a span producer (one root span per request,
// named "{METHOD} {route}") and emits the standard W3C traceparent
// header on outbound calls. Per ADR-0019 step 7 the request id is
// bound to the root span as the attribute `platform.request_id`; the
// trace id is distinct. Sampling follows docs/services/api-gateway/SRS.md §22.
//
// The OTel exporters chosen here match the platform baseline
// (PLATFORM_BASELINE.md §7): an OTLP exporter when
// API_GATEWAY_OTEL_EXPORTER_OTLP_ENDPOINT is set, otherwise a
// stdout exporter that lets local runs see the trace stream
// without requiring a collector. The tracer provider is wrapped in
// a `sdk/trace.TracerProvider` so the rest of the program can hold
// a single, well-typed reference.
package gateway

import (
	"context"
	"fmt"
	"io"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

// Telemetry is a thin wrapper that owns the TracerProvider and the
// global propagator wiring. Callers Shutdown() during graceful
// drain.
type Telemetry struct {
	provider     *sdktrace.TracerProvider
	tracer       trace.Tracer
	shutdownFunc func(context.Context) error
}

// InitTelemetry brings up the OTel SDK with the configured
// exporter and resource attributes. It returns a Telemetry handle
// whose Shutdown() must be called on process exit so spans flush.
func InitTelemetry(ctx context.Context, cfg TelemetryConfig, stdout io.Writer) (*Telemetry, error) {
	res, err := resource.New(ctx,
		resource.WithAttributes(
			attribute.String("service.name", cfg.ServiceName),
			attribute.String("service.version", cfg.ServiceVersion),
			attribute.String("deployment.environment", cfg.Environment),
			attribute.String("cloud.region", cfg.Region),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("telemetry resource: %w", err)
	}

	var exporter sdktrace.SpanExporter
	switch {
	case cfg.OTLPEndpoint != "":
		exporter, err = otlptracegrpc.New(ctx,
			otlptracegrpc.WithEndpoint(cfg.OTLPEndpoint),
			otlptracegrpc.WithInsecure(),
		)
	default:
		if stdout == nil {
			stdout = io.Discard
		}
		exporter, err = stdouttrace.New(
			stdouttrace.WithWriter(stdout),
			stdouttrace.WithPrettyPrint(),
		)
	}
	if err != nil {
		return nil, fmt.Errorf("telemetry exporter: %w", err)
	}

	ratio := cfg.SampleRatio
	if ratio <= 0 {
		ratio = 1.0
	}
	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.TraceIDRatioBased(ratio)),
	)
	otel.SetTracerProvider(provider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
	return &Telemetry{
		provider:     provider,
		tracer:       provider.Tracer("github.com/trips-enjoy/platform/api-gateway"),
		shutdownFunc: provider.Shutdown,
	}, nil
}

// Tracer returns the gateway's named tracer. It is always non-nil.
func (t *Telemetry) Tracer() trace.Tracer {
	if t == nil {
		return otel.Tracer("noop")
	}
	return t.tracer
}

// Shutdown drains pending spans and shuts down the exporter. Safe
// to call multiple times.
func (t *Telemetry) Shutdown(ctx context.Context) error {
	if t == nil || t.shutdownFunc == nil {
		return nil
	}
	return t.shutdownFunc(ctx)
}
