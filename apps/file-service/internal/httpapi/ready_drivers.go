package httpapi

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

// readyDriversHandler implements GET /ready/drivers/{id} per
// docs/services/file-service/README.md §15 ("/ready/drivers/{id}
// flips to false on CB open"). Auth-gated because the response reveals
// driver topology.
func readyDriversHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id := chi.URLParam(r, "id")
		if id == "" {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "driver id required")
			return
		}
		reachable := deps.Drivers.IsReachable(r.Context(), id)
		circuitOpen := deps.Drivers.IsCircuitOpen(id)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"driver_id":    id,
			"reachable":    reachable,
			"circuit_open": circuitOpen,
			"checked_at":   time.Now().UTC().Format(time.RFC3339Nano),
		})
	}
}
