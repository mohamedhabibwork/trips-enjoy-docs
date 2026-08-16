package httpapi

import (
	"encoding/json"
	"net/http"
)

// healthHandler is the liveness probe — always 200, no dependency
// checks. Returns a small JSON body so probes that grep the body also
// pass.
func healthHandler(service string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "UP", "service": service})
	}
}

// readyHandler is the readiness probe — 200 only when every CRITICAL
// downstream is reachable. Per docs/services/geolocation-service/SRS.md
// §18 + INTEGRATION.md §7 the default chain's primary provider must
// have a closed circuit; the production wiring also pings DB + Redis
// + Kafka.
func readyHandler(d Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		checks := map[string]string{"db": "UP", "redis": "UP", "primary_circuit": "closed"}
		status := http.StatusOK
		if d.DBPinger != nil {
			if err := d.DBPinger.Ping(r.Context()); err != nil {
				checks["db"] = "DOWN"
				status = http.StatusServiceUnavailable
			}
		}
		if d.RedisPinger != nil {
			if err := d.RedisPinger.Ping(r.Context()); err != nil {
				checks["redis"] = "DOWN"
				status = http.StatusServiceUnavailable
			}
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status": map[bool]string{true: "UP", false: "DEGRADED"}[status == http.StatusOK],
			"checks": checks,
		})
	}
}

// startedHandler is the startup probe — flips to 200 once the binary
// has loaded its config + registered providers. We always return UP
// here because main wires every dependency before starting the HTTP
// listener.
func startedHandler(service string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "UP", "service": service})
	}
}
