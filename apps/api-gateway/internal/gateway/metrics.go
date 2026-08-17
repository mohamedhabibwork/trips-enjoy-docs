// Package gateway — Prometheus metrics.
//
// Per docs/services/api-gateway/README.md §15 and SRS.md §22 the
// gateway exposes:
//
//	gateway_requests_total{route, method, status}
//	gateway_request_duration_seconds{route, method, status}
//	gateway_upstream_duration_seconds{route, upstream}
//	gateway_rate_limit_rejections_total{route, reason}
//	gateway_jwt_verification_failures_total{reason}
//	gateway_revocation_set_size{realm}
//	gateway_circuit_breaker_state{upstream}
//	gateway_audit_events_emitted_total{event_name, result}
//	gateway_audit_emission_failures_total{event_name}
//	gateway_kafka_publish_duration_seconds{topic, result}
//
// The set is exposed at `/metrics` (Prometheus scrape format) on
// the PUBLIC mux per SRS §22; admin endpoints are on a separate
// port.
package gateway

import (
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metrics owns the Prometheus registry and the named metric
// collectors. New collectors MUST be registered before /metrics
// is served.
type Metrics struct {
	registry *prometheus.Registry

	// RED per route / method / status.
	requestsTotal   *prometheus.CounterVec
	requestDuration *prometheus.HistogramVec
	errorsTotal     *prometheus.CounterVec

	// Upstream RED.
	upstreamDuration *prometheus.HistogramVec

	// Rate limit, JWT, revocation, circuit breaker, audit emit.
	rateLimitRejections *prometheus.CounterVec
	jwtFailures         *prometheus.CounterVec
	revocationSize      *prometheus.GaugeVec
	circuitBreakerState *prometheus.GaugeVec
	auditEventsEmitted  *prometheus.CounterVec
	auditEmitFailures   *prometheus.CounterVec
	kafkaPublish        *prometheus.HistogramVec
}

// NewMetrics builds the registry and registers the collectors.
// Idempotent.
func NewMetrics() *Metrics {
	r := prometheus.NewRegistry()
	m := &Metrics{
		registry: r,
		requestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "gateway_requests_total",
			Help: "Total API gateway responses by stable route, method, and status.",
		}, []string{"route", "method", "status"}),
		requestDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "gateway_request_duration_seconds",
			Help:    "API gateway response duration by route, method, and status.",
			Buckets: prometheus.DefBuckets,
		}, []string{"route", "method", "status"}),
		errorsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "gateway_errors_total",
			Help: "Total API gateway error responses (status >= 400).",
		}, []string{"route", "method", "status"}),
		upstreamDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "gateway_upstream_duration_seconds",
			Help:    "Upstream service round-trip duration by route.",
			Buckets: prometheus.DefBuckets,
		}, []string{"route", "upstream"}),
		rateLimitRejections: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "gateway_rate_limit_rejections_total",
			Help: "Total 429 RATE_LIMITED rejections by route.",
		}, []string{"route"}),
		jwtFailures: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "gateway_jwt_verification_failures_total",
			Help: "JWT validation failures by reason.",
		}, []string{"reason"}),
		revocationSize: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "gateway_revocation_set_size",
			Help: "Approximate size of the in-Redis revocation set.",
		}, []string{"realm"}),
		circuitBreakerState: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "gateway_circuit_breaker_state",
			Help: "Per-upstream circuit breaker state (0=closed,1=half-open,2=open).",
		}, []string{"upstream"}),
		auditEventsEmitted: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "gateway_audit_events_emitted_total",
			Help: "Audit / lifecycle events emitted to Kafka by name and result.",
		}, []string{"event_name", "result"}),
		auditEmitFailures: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "gateway_audit_emission_failures_total",
			Help: "Emit failures by event name (after retries).",
		}, []string{"event_name"}),
		kafkaPublish: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "gateway_kafka_publish_duration_seconds",
			Help:    "Kafka publish duration by topic and result.",
			Buckets: prometheus.DefBuckets,
		}, []string{"topic", "result"}),
	}
	r.MustRegister(
		m.requestsTotal, m.requestDuration, m.errorsTotal,
		m.upstreamDuration, m.rateLimitRejections, m.jwtFailures,
		m.revocationSize, m.circuitBreakerState,
		m.auditEventsEmitted, m.auditEmitFailures, m.kafkaPublish,
	)
	return m
}

// Handler returns the Prometheus scrape HTTP handler.
func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

// Registry exposes the underlying *prometheus.Registry so custom
// collectors can be registered (used by tests).
func (m *Metrics) Registry() *prometheus.Registry { return m.registry }

// Observe records one request observation.
func (m *Metrics) Observe(route, method string, status int, dur time.Duration) {
	labels := prometheus.Labels{"route": route, "method": method, "status": strconv.Itoa(status)}
	m.requestsTotal.With(labels).Inc()
	m.requestDuration.With(labels).Observe(dur.Seconds())
	if status >= http.StatusBadRequest {
		m.errorsTotal.With(labels).Inc()
	}
}

// ObserveUpstream records the upstream round-trip duration.
func (m *Metrics) ObserveUpstream(route, upstream string, dur time.Duration) {
	m.upstreamDuration.With(prometheus.Labels{"route": route, "upstream": upstream}).Observe(dur.Seconds())
}

// IncRateLimitRejection bumps the per-route 429 counter.
func (m *Metrics) IncRateLimitRejection(route string) {
	m.rateLimitRejections.With(prometheus.Labels{"route": route}).Inc()
}

// IncJWTFailure bumps the JWT-failure counter with a reason.
func (m *Metrics) IncJWTFailure(reason string) {
	m.jwtFailures.With(prometheus.Labels{"reason": reason}).Inc()
}

// SetRevocationSize updates the revocation-set gauge.
func (m *Metrics) SetRevocationSize(realm string, size float64) {
	m.revocationSize.With(prometheus.Labels{"realm": realm}).Set(size)
}

// SetCircuitBreakerState updates the breaker-state gauge for one
// upstream. state encodes gobreaker.State as 0/1/2.
func (m *Metrics) SetCircuitBreakerState(upstream string, state float64) {
	m.circuitBreakerState.With(prometheus.Labels{"upstream": upstream}).Set(state)
}

// IncAuditEmitted bumps the audit-emit counter.
func (m *Metrics) IncAuditEmitted(eventName, result string) {
	m.auditEventsEmitted.With(prometheus.Labels{"event_name": eventName, "result": result}).Inc()
}

// IncAuditEmitFailure bumps the emit-failure counter.
func (m *Metrics) IncAuditEmitFailure(eventName string) {
	m.auditEmitFailures.With(prometheus.Labels{"event_name": eventName}).Inc()
}

// ObserveKafkaPublish records one publish attempt.
func (m *Metrics) ObserveKafkaPublish(topic, result string, dur time.Duration) {
	m.kafkaPublish.With(prometheus.Labels{"topic": topic, "result": result}).Observe(dur.Seconds())
}
