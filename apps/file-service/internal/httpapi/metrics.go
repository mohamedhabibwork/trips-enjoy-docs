package httpapi

import (
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// metrics owns the prometheus Registry + every collector the service
// emits. The set is split into:
//
//   - RED per route (requestsTotal, requestDuration, errorsTotal, inFlight)
//   - file-service business KPIs from docs/services/file-service/README.md §15
//     (uploads / scanned / signed-URL / storage bytes / per-driver metrics)
//   - driver-state gauges consumed by the alerts in
//     monitoring/file-service-alerts.yaml
//
// Setters below are no-ops until the corresponding collector is wired
// by the caller. Deps.Metrics is intentionally exported so the service
// can call IncFilesUploaded / SetDriverHealth etc. from cmd/server and
// the files package without exposing the Registry internals.
type Metrics struct {
	registry *prometheus.Registry

	// RED per route.
	requestsTotal   *prometheus.CounterVec
	requestDuration *prometheus.HistogramVec
	errorsTotal     *prometheus.CounterVec
	inFlight        *prometheus.GaugeVec

	// Business KPIs (per README §15 / SRS §22).
	uploadsTotal           *prometheus.CounterVec   // owner_type, mime_class, upload_method, driver_id
	filesScannedTotal      *prometheus.CounterVec   // result = clean|infected|error
	fileStorageBytes       *prometheus.GaugeVec     // driver_id, retention_class
	signedURLSeconds       *prometheus.HistogramVec // result = cache_hit|cache_miss|driver_error, driver_id
	driverHealth           *prometheus.GaugeVec     // driver_id
	driverCircuitOpen      *prometheus.GaugeVec     // driver_id
	driverRequestsTotal    *prometheus.CounterVec   // driver_id, operation, outcome
	driverRequestSeconds   *prometheus.HistogramVec // driver_id, operation
	driverErrorsTotal      *prometheus.CounterVec   // driver_id, operation, error_class
	migrationsVerifyFailed *prometheus.CounterVec   // (no labels; rate() in alert)

	// Outbox + retention/migration state.
	outboxOldestUnpublished prometheus.Gauge
	outboxPending           prometheus.Gauge
}

// newMetrics builds a fresh prometheus Registry + collectors scoped to
// the file_service_* metric family. Every label-set is declared up
// front so all the setters below stay simple.
func NewMetrics() *Metrics {
	m := &Metrics{
		registry: prometheus.NewRegistry(),
	}
	m.requestsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "file_service_requests_total",
		Help: "Total file-service responses by stable route, method, and status.",
	}, []string{"route", "method", "status"})
	m.requestDuration = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "file_service_request_duration_seconds",
		Help:    "file-service response duration by stable route, method, and status.",
		Buckets: prometheus.DefBuckets,
	}, []string{"route", "method", "status"})
	m.errorsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "file_service_errors_total",
		Help: "Total file-service error responses by stable route, method, and status.",
	}, []string{"route", "method", "status"})
	m.inFlight = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "file_service_requests_in_flight",
		Help: "In-flight file-service requests by route and method.",
	}, []string{"route", "method"})

	m.uploadsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "file_service_uploads_total",
		Help: "Total file uploads by owner_type, mime_class, upload_method, and driver_id.",
	}, []string{"owner_type", "mime_class", "upload_method", "driver_id"})
	m.filesScannedTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "file_service_files_scanned_total",
		Help: "Total file scans by result (clean | infected | error).",
	}, []string{"result"})
	m.fileStorageBytes = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "file_service_file_storage_bytes",
		Help: "Bytes currently stored per driver_id and retention_class.",
	}, []string{"driver_id", "retention_class"})
	m.signedURLSeconds = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "file_service_signed_url_seconds",
		Help:    "Signed-URL issuance latency by result and driver_id.",
		Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.2, 0.5, 1, 2.5, 5},
	}, []string{"result", "driver_id"})

	m.driverHealth = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "file_service_storage_driver_health",
		Help: "Storage driver health (1 = healthy, 0 = unhealthy). 0/1 gauge per driver_id.",
	}, []string{"driver_id"})
	m.driverCircuitOpen = prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "file_service_storage_driver_circuit_open",
		Help: "Storage driver circuit-breaker state (1 = open, 0 = closed). Per driver_id.",
	}, []string{"driver_id"})
	m.driverRequestsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "file_service_storage_driver_requests_total",
		Help: "Storage driver operations by driver_id, operation, and outcome.",
	}, []string{"driver_id", "operation", "outcome"})
	m.driverRequestSeconds = prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "file_service_storage_driver_request_seconds",
		Help:    "Storage driver operation latency by driver_id and operation.",
		Buckets: prometheus.DefBuckets,
	}, []string{"driver_id", "operation"})
	m.driverErrorsTotal = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "file_service_storage_driver_errors_total",
		Help: "Storage driver errors by driver_id, operation, and error_class.",
	}, []string{"driver_id", "operation", "error_class"})

	m.migrationsVerifyFailed = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "file_service_migrations_verify_failed_total",
		Help: "Driver-to-driver migrations whose SHA-256 verify failed.",
	}, []string{"from_driver_id", "to_driver_id"})

	m.outboxOldestUnpublished = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "file_service_outbox_oldest_unpublished_seconds",
		Help: "Age in seconds of the oldest unpublished outbox row.",
	})
	m.outboxPending = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "file_service_outbox_pending",
		Help: "Number of unpublished outbox rows.",
	})

	m.registry.MustRegister(
		m.requestsTotal, m.requestDuration, m.errorsTotal, m.inFlight,
		m.uploadsTotal, m.filesScannedTotal, m.fileStorageBytes, m.signedURLSeconds,
		m.driverHealth, m.driverCircuitOpen, m.driverRequestsTotal, m.driverRequestSeconds, m.driverErrorsTotal,
		m.migrationsVerifyFailed,
		m.outboxOldestUnpublished, m.outboxPending,
	)
	return m
}

// handler exposes the registry over /metrics using promhttp.
func (m *Metrics) handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

// observe returns a chi middleware that records RED metrics around the
// downstream handler. The route label is read AFTER the handler runs
// because chi sets the RouteContext only when it dispatches the request
// into the matched subtree.
func (m *Metrics) observe() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			startedAt := time.Now()
			recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(recorder, r)

			route := chi.RouteContext(r.Context()).RoutePattern()
			if route == "" {
				route = "unmatched"
			}
			status := strconv.Itoa(recorder.status)
			m.requestsTotal.WithLabelValues(route, r.Method, status).Inc()
			m.requestDuration.WithLabelValues(route, r.Method, status).Observe(time.Since(startedAt).Seconds())
			if recorder.status >= http.StatusBadRequest {
				m.errorsTotal.WithLabelValues(route, r.Method, status).Inc()
			}
		})
	}
}

// ----- Setters (called by cmd/server + the files package) -----

// IncFilesUploaded records one successful upload.
func (m *Metrics) IncFilesUploaded(ownerType, mimeClass, uploadMethod, driverID string) {
	m.uploadsTotal.WithLabelValues(ownerType, mimeClass, uploadMethod, driverID).Inc()
}

// IncFilesScanned records one scan result.
func (m *Metrics) IncFilesScanned(result string) {
	m.filesScannedTotal.WithLabelValues(result).Inc()
}

// ObserveSignedURL records a signed-URL issuance latency with a
// result label (cache_hit | cache_miss | driver_error).
func (m *Metrics) ObserveSignedURL(driverID, result string, d time.Duration) {
	m.signedURLSeconds.WithLabelValues(result, driverID).Observe(d.Seconds())
}

// SetDriverHealth sets the driver_health gauge (1 = healthy).
func (m *Metrics) SetDriverHealth(driverID string, healthy bool) {
	v := 0.0
	if healthy {
		v = 1.0
	}
	m.driverHealth.WithLabelValues(driverID).Set(v)
}

// SetDriverCircuitOpen sets the driver_circuit_open gauge.
func (m *Metrics) SetDriverCircuitOpen(driverID string, open bool) {
	v := 0.0
	if open {
		v = 1.0
	}
	m.driverCircuitOpen.WithLabelValues(driverID).Set(v)
}

// ObserveDriver records one storage-driver operation.
func (m *Metrics) ObserveDriver(driverID, operation, outcome string, d time.Duration, errClass string) {
	m.driverRequestsTotal.WithLabelValues(driverID, operation, outcome).Inc()
	m.driverRequestSeconds.WithLabelValues(driverID, operation).Observe(d.Seconds())
	if errClass != "" {
		m.driverErrorsTotal.WithLabelValues(driverID, operation, errClass).Inc()
	}
}

// IncMigrationVerifyFailed records one SHA-256 verify failure.
func (m *Metrics) IncMigrationVerifyFailed(fromDriverID, toDriverID string) {
	m.migrationsVerifyFailed.WithLabelValues(fromDriverID, toDriverID).Inc()
}

// SetOutboxState publishes the current outbox lag.
func (m *Metrics) SetOutboxState(oldestSeconds float64, pending int) {
	m.outboxOldestUnpublished.Set(oldestSeconds)
	m.outboxPending.Set(float64(pending))
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

// metricsFromDeps returns the shared metrics handle from Deps, creating
// a fresh one if the caller didn't supply one. Both the public and
// admin routers call this so the underlying prometheus.Registry is
// shared across both HTTP listeners.
func metricsFromDeps(deps Deps) *Metrics {
	if deps.Metrics != nil {
		return deps.Metrics
	}
	return NewMetrics()
}
