package httpapi

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/cors"

	"github.com/trips-enjoy/platform/geolocation-service/internal/auth"
)

// NewRouter assembles the public chi mux: request-id + CORS + RED
// metrics + health/ready/started + OpenAPI + the geocoding + admin
// routes per INTEGRATION.md.
func NewRouter(deps Deps) http.Handler {
	m := newMetrics()
	r := chi.NewRouter()
	r.Use(RequestID)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins: []string{"*"},
		AllowedMethods: []string{http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete, http.MethodOptions},
		AllowedHeaders: []string{
			"Accept", "Authorization", "Content-Type", "Idempotency-Key",
			"X-Correlation-Id", "X-Request-Id", "X-User-Id", "X-User-Type",
			"X-Roles", "X-Scopes", "X-Tenant-Id", "X-Signature",
		},
		ExposedHeaders:   []string{"RateLimit-Limit", "RateLimit-Remaining", "X-Correlation-Id", "X-Request-Id"},
		AllowCredentials: true,
		MaxAge:           300,
	}))
	r.Use(m.observe())

	r.Get("/health", healthHandler(deps.ServiceName))
	r.Get("/ready", readyHandler(deps))
	r.Get("/started", startedHandler(deps.ServiceName))
	r.Get("/openapi.json", openAPIHandler())
	r.Get("/docs", docsHandler)
	r.Handle("/metrics", m.handler())

	// Public read/write routes per INTEGRATION.md §1.
	r.Route("/v1", func(r chi.Router) {
		r.Use(auth.Middleware)

		r.Post("/geocodes", geocodeForwardHandler(deps))
		r.Get("/geocodes/reverse", geocodeReverseHandler(deps))
		r.Post("/etas", etaHandler(deps))
		r.Post("/routes", routeHandler(deps))
		r.Get("/cities/lookup", citiesLookupHandler(deps))

		// Admin routes per INTEGRATION.md §1.6 + §5 — gated by
		// role-based middleware + HMAC inside the handler.
		r.Route("/admin", func(r chi.Router) {
			r.Use(auth.RequireRole("admin"))

			r.Post("/cache/purge", purgeCacheHandler(deps))
			r.Get("/providers", listProvidersHandler(deps))
			r.Get("/providers/rotate", rotateProviderHandler(deps))
			r.Post("/providers/rotate", rotateProviderHandler(deps))

			r.Route("/providers/{vendor_id}", func(r chi.Router) {
				r.Get("/", getProviderHandler(deps))
				r.Patch("/", patchProviderHandler(deps))
				r.Post("/test", testProviderHandler(deps))
			})
			r.Put("/region-chains/{region}/{capability}", setRegionChainHandler(deps))
		})
	})

	return r
}

// NewAdminRouter returns a chi mux that mounts the ops-only
// /admin/v1/* surface on the admin port (per TECH.md §10.4). It is
// distinct from the public mux (which serves /v1/admin/* per
// INTEGRATION.md) so the ops tooling can reach it without sharing a
// listener with public traffic.
func NewAdminRouter(deps Deps) http.Handler {
	m := newMetrics()
	r := chi.NewRouter()
	r.Use(RequestID)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins: []string{"*"},
		AllowedMethods: []string{http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete, http.MethodOptions},
		AllowedHeaders: []string{"Accept", "Authorization", "Content-Type", "X-Correlation-Id", "X-Request-Id", "X-User-Id", "X-Roles", "X-Signature"},
		ExposedHeaders: []string{"X-Correlation-Id", "X-Request-Id"},
		MaxAge:         300,
	}))
	r.Use(m.observe())

	r.Get("/health", healthHandler(deps.ServiceName+" (admin)"))
	r.Get("/ready", readyHandler(deps))
	r.Get("/started", startedHandler(deps.ServiceName+" (admin)"))

	r.Route("/admin/v1", func(r chi.Router) {
		r.Use(auth.Middleware)
		r.Use(auth.RequireRole("admin"))

		// Ops endpoints per TECH.md §10.4 — these are ops-only
		// duplicates of the public admin surface.
		r.Route("/providers/{vendor_id}", func(r chi.Router) {
			r.Post("/probe", forceProbeHandler(deps))
		})
	})

	return r
}
