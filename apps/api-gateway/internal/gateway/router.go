// Package gateway — router assembly.
//
// NewRouter builds the public + infrastructure HTTP handler tree.
// The order of middleware matters:
//
//  1. RequestIDMiddleware   (must run first; ADR-0019 step 1)
//  2. CORSMiddleware        (per-channel allowlist)
//  3. OTel tracing          (root span)
//  4. Metrics.observe       (status capture for observability)
//  5. Reverse proxy         (catch-all NotFound)
//  6. /metrics (Prometheus scrape)
//  7. /openapi.json, /docs (openapi + swagger UI)
//  8. /health, /ready, /started (probe endpoints)
//
// The admin mux is mounted separately (router.go returns a tuple)
// so the public port does not expose admin endpoints
// (TECH.md §10.5).
package gateway

import (
	"net/http"
	"sort"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/cors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel/trace"
)

// RouterDeps bundles the collaborators the router needs.
type RouterDeps struct {
	Proxy     http.Handler
	Health    HealthDeps
	OpenAPI   http.Handler
	Metrics   *Metrics
	Snapshots *SnapshotStore
	Circuits  *CircuitRegistry
	Trace     trace.Tracer
}

// HealthDeps carries what /ready checks.
type HealthDeps struct {
	Redis     *RedisClient
	Verifiers *Verifiers
	StartTime time.Time
}

// Routers holds the public and admin handlers.
type Routers struct {
	Public http.Handler
	Admin  http.Handler
}

// NewRouter assembles the public handler.
func NewRouter(d RouterDeps) http.Handler {
	router := chi.NewRouter()
	router.Use(RequestIDMiddleware())
	router.Use(cors.Handler(cors.Options{
		AllowedOrigins: func() []string {
			if d.Snapshots != nil {
				if s := d.Snapshots.Load(); s != nil {
					return s.CORS
				}
			}
			return []string{}
		}(),
		AllowedMethods:   []string{http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete, http.MethodOptions},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id", "X-Request-Id", "X-Api-Key"},
		ExposedHeaders:   []string{"RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset", "Retry-After", "X-Correlation-Id", "X-Request-Id"},
		AllowCredentials: true,
		MaxAge:           300,
	}))
	// otelhttp wraps the chain so the root span captures the full
	// chain and the response status; it propagates trace context
	// from inbound traceparent headers.
	router.Use(otelhttp.NewMiddleware("api-gateway", otelhttp.WithSpanNameFormatter(func(s string, r *http.Request) string {
		return r.Method + " " + r.URL.Path
	})))
	router.Use(metricsMiddleware(d.Metrics, d.Snapshots))

	router.Get("/health", healthHandler(d.Health))
	router.Get("/ready", readyHandler(d.Health))
	router.Get("/started", startedHandler(d.Health))
	router.Get("/healthz/downstream", downstreamHealthHandler(d))
	router.Get("/openapi.json", d.OpenAPI.ServeHTTP)
	router.Get("/openapi/{service}.json", makeServiceOpenAPIHandler(d.Snapshots).ServeHTTP)
	router.Get("/docs", docsHandler(d.Snapshots).ServeHTTP)
	router.Handle("/metrics", d.Metrics.Handler())
	router.NotFound(d.Proxy.ServeHTTP)
	router.MethodNotAllowed(d.Proxy.ServeHTTP)
	return router
}

// metricsMiddleware records request observations (status /
// duration) into Prometheus.
func metricsMiddleware(m *Metrics, s *SnapshotStore) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rec := wrapRecorder(w)
			next.ServeHTTP(rec, r)
			route := routeOf(s, r.URL.Path)
			m.Observe(route, r.Method, rec.status, time.Since(start))
		})
	}
}

func healthHandler(d HealthDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, map[string]any{
			"status":   "UP",
			"service":  "api-gateway",
			"uptime_s": int(time.Since(d.StartTime).Seconds()),
			"started":  d.StartTime.UTC().Format(time.RFC3339),
		})
	}
}

func readyHandler(d HealthDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if d.Redis != nil {
			if err := d.Redis.Ping(r.Context()); err != nil {
				WriteError(r.Context(), w, r, http.StatusServiceUnavailable, CodeServiceUnavailable,
					"Redis unreachable.", nil)
				return
			}
		}
		if d.Verifiers != nil && len(d.Verifiers.IssuerURLs()) == 0 {
			WriteError(r.Context(), w, r, http.StatusServiceUnavailable, CodeServiceUnavailable,
				"Keycloak issuers not configured.", nil)
			return
		}
		writeJSON(w, map[string]any{"status": "READY", "service": "api-gateway"})
	}
}

func startedHandler(d HealthDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, map[string]any{
			"status":   "STARTED",
			"service":  "api-gateway",
			"started":  d.StartTime.UTC().Format(time.RFC3339),
			"uptime_s": int(time.Since(d.StartTime).Seconds()),
		})
	}
}

func writeJSON(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = writeJSONBody(w, body)
}

func makeServiceOpenAPIHandler(s *SnapshotStore) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		service := chiURLParam(r, "service")
		snap := s.Load()
		if snap == nil {
			WriteError(r.Context(), w, r, http.StatusServiceUnavailable, CodeServiceUnavailable,
				"Gateway has not loaded its config.", nil)
			return
		}
		target, ok := openAPIURL(snap.Routes, service)
		if !ok {
			WriteError(r.Context(), w, r, http.StatusNotFound, CodeNotFound,
				"The requested OpenAPI specification was not found.", nil)
			return
		}
		// Fetch with a short, fixed timeout.
		client := &http.Client{Timeout: 5 * time.Second}
		req, _ := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
		req.Header.Set("X-Request-Id", RequestID(r.Context()))
		req.Header.Set("X-Correlation-Id", RequestID(r.Context()))
		resp, err := client.Do(req)
		if err != nil || resp.StatusCode != http.StatusOK {
			if resp != nil {
				resp.Body.Close()
			}
			WriteError(r.Context(), w, r, http.StatusBadGateway, CodeDependencyUpstream,
				"The requested OpenAPI specification is unavailable.", nil)
			return
		}
		defer resp.Body.Close()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_, _ = copyAll(w, resp.Body)
	})
}

func docsHandler(s *SnapshotStore) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		snap := s.Load()
		if snap == nil {
			WriteError(r.Context(), w, r, http.StatusServiceUnavailable, CodeServiceUnavailable,
				"Gateway has not loaded its config.", nil)
			return
		}
		services := map[string]struct{}{}
		for _, r := range snap.Routes {
			services[r.Service] = struct{}{}
		}
		names := make([]string, 0, len(services))
		for n := range services {
			names = append(names, n)
		}
		sort.Strings(names)
		urls := make([]map[string]string, 0, len(names)+1)
		urls = append(urls, map[string]string{"name": "platform", "url": "/openapi.json"})
		for _, n := range names {
			urls = append(urls, map[string]string{"name": n, "url": "/openapi/" + n + ".json"})
		}
		enc, _ := jsonMarshal(urls)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte("<!doctype html><html><head><link rel=\"stylesheet\" href=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui.css\"></head><body><div id=\"swagger-ui\"></div><script src=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js\"></script><script>SwaggerUIBundle({urls:" + string(enc) + ",dom_id:'#swagger-ui'});</script></body></html>"))
	})
}

// chiURLParam extracts a chi URL param. chi stores route params
// under context.Context[chi.RouteCtxKey]; the helper isolates the
// chi detail so the rest of the package doesn't depend on its
// internal types.
func chiURLParam(r *http.Request, key string) string {
	if v, ok := r.Context().Value(chiRouteCtxKey(r)).(map[string]string); ok {
		return v[key]
	}
	return ""
}

// Used by chi internally; included to keep the imports correct.
var (
	_ = promhttp.Handler
)
