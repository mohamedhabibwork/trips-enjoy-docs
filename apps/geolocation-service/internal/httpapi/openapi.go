package httpapi

import (
	"encoding/json"
	"net/http"
)

// openAPIHandler serves the OpenAPI 3.1 catalog for the public +
// admin surface per docs/architecture/API_STANDARDS.md §18 (every
// service publishes its spec at /openapi.json). The per-endpoint
// schemas are simplified placeholders; the production follow-up PR
// generates the spec from the canonical contracts in
// docs/services/geolocation-service/INTEGRATION.md.
func openAPIHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"openapi": "3.1.0",
			"info": map[string]string{
				"title":       "geolocation-service",
				"version":     "1.0.0",
				"description": "Stateless geospatial adapter: geocode / reverse / ETA / route / last-known city, in front of a multi-provider chain (Google, Mapbox, HERE, OSRM, Valhalla, Nominatim, Pelias, Photon, mock).",
			},
			"servers": []map[string]string{{"url": "/", "description": "same-origin"}},
			"paths": map[string]any{
				"/v1/geocodes":               map[string]any{"post": geocodeForwardOp()},
				"/v1/geocodes/reverse":       map[string]any{"get": geocodeReverseOp()},
				"/v1/etas":                   map[string]any{"post": etaOp()},
				"/v1/routes":                 map[string]any{"post": routeOp()},
				"/v1/cities/lookup":          map[string]any{"get": citiesLookupOp()},
				"/v1/admin/cache/purge":      map[string]any{"post": adminPurgeOp()},
				"/v1/admin/providers":        map[string]any{"get": adminListProvidersOp()},
				"/v1/admin/providers/rotate": map[string]any{"post": adminRotateProviderOp()},
				"/v1/admin/providers/{vendor_id}": map[string]any{
					"get":   adminGetProviderOp(),
					"patch": adminPatchProviderOp(),
				},
				"/v1/admin/providers/{vendor_id}/test":          map[string]any{"post": adminTestProviderOp()},
				"/v1/admin/region-chains/{region}/{capability}": map[string]any{"put": adminRegionChainOp()},
				"/admin/v1/providers/{vendor_id}/probe":         map[string]any{"post": adminForceProbeOp()},
			},
			"components": map[string]any{
				"securitySchemes": map[string]any{
					"bearerAuth": map[string]any{
						"type":         "http",
						"scheme":       "bearer",
						"bearerFormat": "JWT",
					},
				},
			},
		})
	}
}

// docsHandler serves the Swagger UI entry point.
func docsHandler(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(`<!doctype html><html><head><link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"></head><body><div id="swagger-ui"></div><script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script><script>SwaggerUIBundle({url:"/openapi.json",dom_id:"#swagger-ui"});</script></body></html>`))
}

// Each op() helper builds the minimal OpenAPI shape for one endpoint.

func geocodeForwardOp() map[string]any {
	return map[string]any{
		"summary":     "Forward geocode a free-text address",
		"operationId": "geocodeForward",
		"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/GeocodeForwardRequest"}}}},
		"responses":   standardResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func geocodeReverseOp() map[string]any {
	return map[string]any{
		"summary":     "Reverse geocode a coordinate",
		"operationId": "geocodeReverse",
		"parameters": []map[string]any{
			{"name": "lat", "in": "query", "required": true, "schema": map[string]string{"type": "number"}},
			{"name": "lon", "in": "query", "required": true, "schema": map[string]string{"type": "number"}},
			{"name": "locale", "in": "query", "required": true, "schema": map[string]string{"type": "string"}},
			{"name": "approximate", "in": "query", "required": false, "schema": map[string]string{"type": "boolean"}},
		},
		"responses": standardResponses(),
		"security":  []map[string]any{{"bearerAuth": []string{}}},
	}
}

func etaOp() map[string]any {
	return map[string]any{
		"summary":     "Compute an ETA between two points",
		"operationId": "eta",
		"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/EtaRequest"}}}},
		"responses":   standardResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func routeOp() map[string]any {
	return map[string]any{
		"summary":     "Compute a route (polyline + steps)",
		"operationId": "route",
		"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/RouteRequest"}}}},
		"responses":   standardResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{}}},
	}
}

func citiesLookupOp() map[string]any {
	return map[string]any{
		"summary":     "Resolve a coordinate to the platform's city_id",
		"operationId": "citiesLookup",
		"parameters": []map[string]any{
			{"name": "lat", "in": "query", "required": true, "schema": map[string]string{"type": "number"}},
			{"name": "lon", "in": "query", "required": true, "schema": map[string]string{"type": "number"}},
		},
		"responses": standardResponses(),
		"security":  []map[string]any{{"bearerAuth": []string{}}},
	}
}

func adminPurgeOp() map[string]any {
	return map[string]any{
		"summary":     "Force a cache purge by filter (admin)",
		"operationId": "adminPurgeCache",
		"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/PurgeRequest"}}}},
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminListProvidersOp() map[string]any {
	return map[string]any{
		"summary":     "List every configured provider + circuit state",
		"operationId": "adminListProviders",
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminRotateProviderOp() map[string]any {
	return map[string]any{
		"summary":     "Rotate a provider key (high-severity audit)",
		"operationId": "adminRotateProvider",
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"platform_engineer"}}},
	}
}

func adminGetProviderOp() map[string]any {
	return map[string]any{
		"summary":     "Get one provider + recent probes",
		"operationId": "adminGetProvider",
		"parameters":  []map[string]any{{"name": "vendor_id", "in": "path", "required": true, "schema": map[string]string{"type": "string"}}},
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminPatchProviderOp() map[string]any {
	return map[string]any{
		"summary":     "Toggle enabled / rate limits / metadata on a provider",
		"operationId": "adminPatchProvider",
		"parameters":  []map[string]any{{"name": "vendor_id", "in": "path", "required": true, "schema": map[string]string{"type": "string"}}},
		"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/ProviderPatch"}}}},
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"platform_engineer"}}},
	}
}

func adminTestProviderOp() map[string]any {
	return map[string]any{
		"summary":     "Invoke one provider directly (bypass chain)",
		"operationId": "adminTestProvider",
		"parameters":  []map[string]any{{"name": "vendor_id", "in": "path", "required": true, "schema": map[string]string{"type": "string"}}},
		"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/TestProviderRequest"}}}},
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"admin"}}},
	}
}

func adminRegionChainOp() map[string]any {
	return map[string]any{
		"summary":     "Set the provider chain for (region, capability)",
		"operationId": "adminSetRegionChain",
		"parameters": []map[string]any{
			{"name": "region", "in": "path", "required": true, "schema": map[string]string{"type": "string"}},
			{"name": "capability", "in": "path", "required": true, "schema": map[string]string{"type": "string"}},
		},
		"requestBody": map[string]any{"required": true, "content": map[string]any{"application/json": map[string]any{"schema": map[string]string{"$ref": "#/components/schemas/RegionChainEdit"}}}},
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"platform_engineer"}}},
	}
}

func adminForceProbeOp() map[string]any {
	return map[string]any{
		"summary":     "Force an immediate health probe",
		"operationId": "adminForceProbe",
		"parameters":  []map[string]any{{"name": "vendor_id", "in": "path", "required": true, "schema": map[string]string{"type": "string"}}},
		"responses":   adminResponses(),
		"security":    []map[string]any{{"bearerAuth": []string{"geolocation.admin"}}},
	}
}

func standardResponses() map[string]any {
	return map[string]any{
		"200": map[string]any{"description": "OK"},
		"400": map[string]any{"description": "VALIDATION_FAILED"},
		"401": map[string]any{"description": "UNAUTHENTICATED"},
		"403": map[string]any{"description": "FORBIDDEN"},
		"404": map[string]any{"description": "NOT_FOUND or CITY_NOT_FOUND"},
		"422": map[string]any{"description": "ADDRESS_UNSUPPORTED_REGION or IDEMPOTENCY_KEY_REUSED"},
		"429": map[string]any{"description": "RATE_LIMITED"},
		"503": map[string]any{"description": "CIRCUIT_OPEN or DEPENDENCY_UNAVAILABLE"},
		"504": map[string]any{"description": "DEPENDENCY_TIMEOUT"},
	}
}

func adminResponses() map[string]any {
	r := standardResponses()
	r["409"] = map[string]any{"description": "SIGNATURE_INVALID or CO_SIGNATURE_MISSING"}
	r["501"] = map[string]any{"description": "ENDPOINT_RETIRED"}
	return r
}
