package httpapi

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/cors"

	"github.com/trips-enjoy/platform/file-service/internal/auth"
)

// NewAdminRouter returns a chi mux that mounts the ops-only
// /admin/v1/* surface on the admin port (per TECH.md §10.4). It is
// distinct from the public mux (which serves /v1/admin/* per
// INTEGRATION.md) so the ops tooling can reach it without sharing a
// listener with public traffic. Both share the same downstream admin
// handlers, so the contract is identical regardless of which URL a
// caller uses.
func NewAdminRouter(deps Deps) http.Handler {
	m := metricsFromDeps(deps)
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
		r.Post("/files/{id}/purge", adminNotImplementedHandler("purge"))
		r.Post("/files/{id}/quarantine", adminNotImplementedHandler("quarantine"))
		r.Post("/files/{id}/rescan", adminNotImplementedHandler("rescan"))
		r.Get("/files/{id}/driver", driverAssignmentHandler(deps))
		r.Get("/drivers", listDriversHandler(deps))
		r.Post("/drivers/{id}", adminNotImplementedHandler("update-driver"))
		r.Post("/drivers/{id}/pin", pinDriverHandler(deps))
		r.Post("/migrations", enqueueMigrationHandler(deps))
		r.Get("/migrations/{id}", getMigrationHandler(deps))
		r.Post("/migrations/{id}/cancel", adminNotImplementedHandler("cancel-migration"))
		r.Get("/ready/drivers/{id}", readyDriversHandler(deps))
	})

	return r
}

func adminNotImplementedHandler(name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		writeJSONStatus(w, http.StatusNotImplemented, map[string]any{
			"code":    "ENDPOINT_RETIRED",
			"message": "admin ops endpoint " + name + " is not implemented in the dev scaffold",
		})
	}
}
