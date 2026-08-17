package httpapi

import (
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// metrics owns the prometheus Registry + the geolocation-specific
// collectors documented in docs/services/geolocation-service/SRS.md §22.
type metrics struct {
	registry *prometheus.Registry

	requestsTotal   *prometheus.CounterVec
	requestDuration *prometheus.HistogramVec
	errorsTotal     *prometheus.CounterVec
	inFlight        *prometheus.GaugeVec

	geocodeRequestsTotal *prometheus.CounterVec
	etaRequestsTotal     *prometheus.CounterVec
	routeRequestsTotal   *prometheus.CounterVec
	cacheHitRatio        *prometheus.GaugeVec
	vendorCircuitState   *prometheus.GaugeVec
	vendorRateRemaining  *prometheus.GaugeVec
	providerChainLength  *prometheus.GaugeVec
	providerFallback     *prometheus.CounterVec
}

// newMetrics builds a fresh prometheus Registry + collectors scoped to
// the geolocation_* metric family.
func newMetrics() *metrics {
	m := &metrics{
		registry: prometheus.NewRegistry(),
		requestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "geolocation_requests_total",
			Help: "Total geolocation-service responses by stable route, method, and status.",
		}, []string{"route", "method", "status"}),
		requestDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "geolocation_request_duration_seconds",
			Help:    "geolocation-service response duration by stable route, method, and status.",
			Buckets: prometheus.DefBuckets,
		}, []string{"route", "method", "status"}),
		errorsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "geolocation_errors_total",
			Help: "Total geolocation-service error responses by stable route, method, and status.",
		}, []string{"route", "method", "status"}),
		inFlight: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "geolocation_requests_in_flight",
			Help: "In-flight geolocation-service requests by route and method.",
		}, []string{"route", "method"}),
		geocodeRequestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "geocode_requests_total",
			Help: "Total geocode requests by cache_hit, vendor_id, region, capability, status.",
		}, []string{"cache_hit", "vendor_id", "region", "capability", "status"}),
		etaRequestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "eta_requests_total",
			Help: "Total ETA requests by cache_hit, vendor_id, region, capability, status.",
		}, []string{"cache_hit", "vendor_id", "region", "capability", "status"}),
		routeRequestsTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "route_requests_total",
			Help: "Total route requests by cache_hit, vendor_id, region, capability, status.",
		}, []string{"cache_hit", "vendor_id", "region", "capability", "status"}),
		cacheHitRatio: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "geolocation_cache_hit_ratio",
			Help: "Cache hit ratio per resource (geocode | eta | route).",
		}, []string{"resource"}),
		vendorCircuitState: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "vendor_circuit_state",
			Help: "Vendor circuit-breaker state (0=closed, 1=half_open, 2=open).",
		}, []string{"vendor_id"}),
		vendorRateRemaining: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "vendor_rate_limit_remaining",
			Help: "Vendor rate-limit remaining tokens.",
		}, []string{"vendor_id"}),
		providerChainLength: prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: "provider_chain_length",
			Help: "Length of the resolved provider chain per (region, capability).",
		}, []string{"region", "capability"}),
		providerFallback: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "provider_fallback_activations_total",
			Help: "Total provider fallback activations by from_vendor and to_vendor.",
		}, []string{"from_vendor", "to_vendor", "region"}),
	}
	m.registry.MustRegister(
		m.requestsTotal, m.requestDuration, m.errorsTotal, m.inFlight,
		m.geocodeRequestsTotal, m.etaRequestsTotal, m.routeRequestsTotal,
		m.cacheHitRatio, m.vendorCircuitState, m.vendorRateRemaining,
		m.providerChainLength, m.providerFallback,
	)
	return m
}

// handler exposes the registry over /metrics using promhttp.
func (m *metrics) handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

// observe returns a chi middleware that records RED metrics around
// the downstream handler. The route label uses chi.RouteContext so the
// value is the registered pattern (e.g. "/v1/geocodes") not the
// literal URL.
func (m *metrics) observe() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			route := chi.RouteContext(r.Context()).RoutePattern()
			if route == "" {
				route = "unmatched"
			}
			startedAt := time.Now()
			m.inFlight.WithLabelValues(route, r.Method).Inc()
			recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
			defer m.inFlight.WithLabelValues(route, r.Method).Dec()
			next.ServeHTTP(recorder, r)

			labels := prometheus.Labels{
				"route":  route,
				"method": r.Method,
				"status": strconv.Itoa(recorder.status),
			}
			m.requestsTotal.With(labels).Inc()
			m.requestDuration.With(labels).Observe(time.Since(startedAt).Seconds())
			if recorder.status >= http.StatusBadRequest {
				m.errorsTotal.With(labels).Inc()
			}
		})
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (w *statusRecorder) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusRecorder) Unwrap() http.Handler { return nil }
