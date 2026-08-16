// Package observability provides the OpenTelemetry tracer + meter
// provider initialisation shared by every Go service. Mirrors the
// `platform-spring-boot-observability` Kotlin module and the
// `platform_python.observability` package. The MDC field "request_id"
// is the canonical correlation hook (ADR-0019).
package observability

import (
	"context"
	"os"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

// Config holds the OTel config knobs.
type Config struct {
	Service    string
	Env        string
	Region     string
	Tenant     string
	OTLPEndpoint string
	UseStdoutFallback bool
}

// DefaultConfig returns the platform defaults.
func DefaultConfig(service string) Config {
	return Config{
		Service:  service,
		Env:      os.Getenv("PLATFORM_ENV"),
		Region:   os.Getenv("PLATFORM_REGION"),
		Tenant:   os.Getenv("PLATFORM_TENANT"),
		OTLPEndpoint: os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
	}
}

// ShutdownFunc is returned by InitTracer and must be called when
// the service stops.
type ShutdownFunc func(context.Context) error

// InitTracer initialises the global TracerProvider. Returns a shutdown
// function that must be called on service exit. Falls back to the
// stdout exporter when OTLP endpoint is not set.
func InitTracer(ctx context.Context, cfg Config) (ShutdownFunc, error) {
	res, err := resource.New(ctx,
		resource.WithAttributes(
			semconv.ServiceName(cfg.Service),
			semconv.DeploymentEnvironment(cfg.Env),
			semconv.CloudRegion(cfg.Region),
		),
	)
	if err != nil {
		return nil, err
	}

	var exporter sdktrace.SpanExporter
	if cfg.OTLPEndpoint != "" {
		exporter, err = otlptrace.New(ctx, otlptracegrpc.NewClient(
			otlptracegrpc.WithEndpoint(cfg.OTLPEndpoint),
			otlptracegrpc.WithInsecure(),
		))
		if err != nil {
			return nil, err
		}
	} else if cfg.UseStdoutFallback {
		exporter, err = stdouttrace.New(stdouttrace.WithPrettyPrint())
		if err != nil {
			return nil, err
		}
	} else {
		// No exporter — use a no-op.
		otel.SetTracerProvider(sdktrace.NewTracerProvider(
			sdktrace.WithSampler(sdktrace.NeverSample()),
		))
		return func(c context.Context) error { return nil }, nil
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter, sdktrace.WithBatchTimeout(5*time.Second)),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
	return tp.Shutdown, nil
}
