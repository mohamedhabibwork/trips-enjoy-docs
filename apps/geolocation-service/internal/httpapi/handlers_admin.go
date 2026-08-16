package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"

	"github.com/trips-enjoy/platform/geolocation-service/internal/admin"
	"github.com/trips-enjoy/platform/geolocation-service/internal/auth"
	"github.com/trips-enjoy/platform/geolocation-service/internal/chain"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// purgeCacheHandler handles POST /v1/admin/cache/purge.
func purgeCacheHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !adminVerifyHMAC(r, deps.HMACSecret) {
			WriteError(w, r, http.StatusConflict, "SIGNATURE_INVALID", "HMAC signature missing or invalid")
			return
		}
		if r.Header.Get("Idempotency-Key") == "" {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "Idempotency-Key header required")
			return
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var req admin.PurgeRequest
		if err := json.Unmarshal(body, &req); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		principal, _ := auth.FromContext(r.Context())
		correlationID := RequestIDFromContext(r.Context())
		resp, err := deps.Admin.PurgeCache(r.Context(), req, principal.UserID, "admin", correlationID)
		if err != nil {
			mapAdminError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusAccepted, resp)
	}
}

// listProvidersHandler handles GET /v1/admin/providers.
func listProvidersHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		providers := deps.Admin.ListProviders()
		writeJSONStatus(w, http.StatusOK, map[string]any{
			"providers":   providers,
			"occurred_at": timeTNow(),
		})
	}
}

// getProviderHandler handles GET /v1/admin/providers/{vendor_id}.
func getProviderHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		vendorID := chi.URLParam(r, "vendor_id")
		summary, probes, ok := deps.Admin.GetProvider(vendorID)
		if !ok {
			WriteError(w, r, http.StatusNotFound, "NOT_FOUND", "vendor not registered")
			return
		}
		writeJSONStatus(w, http.StatusOK, map[string]any{
			"provider":      summary,
			"recent_probes": probes,
			"occurred_at":   timeTNow(),
		})
	}
}

// testProviderHandler handles POST /v1/admin/providers/{vendor_id}/test.
func testProviderHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		vendorID := chi.URLParam(r, "vendor_id")
		if r.Header.Get("Idempotency-Key") == "" {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "Idempotency-Key header required")
			return
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var req struct {
			Capability string         `json:"capability"`
			Query      map[string]any `json:"query"`
		}
		if err := json.Unmarshal(body, &req); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		result, role, err := deps.Admin.TestProvider(r.Context(), vendorID, provider.Capability(req.Capability), req.Query)
		if err != nil {
			mapAdminError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, map[string]any{
			"vendor_id":   vendorID,
			"role":        role,
			"result":      result,
			"occurred_at": timeTNow(),
		})
	}
}

// setRegionChainHandler handles PUT /v1/admin/region-chains/{region}/{capability}.
func setRegionChainHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Idempotency-Key") == "" {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "Idempotency-Key header required")
			return
		}
		region := chi.URLParam(r, "region")
		cap := chi.URLParam(r, "capability")
		if !chain.IsValidRegion(region) {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid region (must be 'default', 'country:<ISO2>', or 'city:<uuid>')")
			return
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var edit admin.RegionChainEdit
		if err := json.Unmarshal(body, &edit); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		principal, _ := auth.FromContext(r.Context())
		correlationID := RequestIDFromContext(r.Context())
		if err := deps.Admin.SetRegionChain(chain.Region(region), provider.Capability(cap), edit, principal.UserID, correlationID); err != nil {
			mapAdminError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, map[string]any{
			"region":     region,
			"capability": cap,
			"chain":      edit.Chain,
			"applied_at": timeTNow(),
		})
	}
}

// patchProviderHandler handles PATCH /v1/admin/providers/{vendor_id}.
func patchProviderHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		vendorID := chi.URLParam(r, "vendor_id")
		if r.Header.Get("Idempotency-Key") == "" {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "Idempotency-Key header required")
			return
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var patch admin.ProviderPatch
		if err := json.Unmarshal(body, &patch); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		principal, _ := auth.FromContext(r.Context())
		correlationID := RequestIDFromContext(r.Context())
		cfg, err := deps.Admin.PatchProvider(vendorID, patch, principal.UserID, correlationID)
		if err != nil {
			mapAdminError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, map[string]any{
			"vendor_id":  vendorID,
			"config":     cfg,
			"applied_at": timeTNow(),
		})
	}
}

// rotateProviderHandler handles POST /v1/admin/providers/rotate. The
// production wiring performs the Vault key swap; the dev scaffold
// returns 501 ENDPOINT_RETIRED and records an audit row.
func rotateProviderHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		principal, _ := auth.FromContext(r.Context())
		correlationID := RequestIDFromContext(r.Context())
		if err := deps.Admin.RotateProvider(r.Context(), principal.UserID, correlationID); err != nil {
			mapAdminError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, map[string]any{"rotated": true, "occurred_at": timeTNow()})
	}
}

// forceProbeHandler handles POST /admin/v1/providers/{vendor_id}/probe.
func forceProbeHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		vendorID := chi.URLParam(r, "vendor_id")
		probe, err := deps.Admin.ForceProbe(r.Context(), vendorID)
		if err != nil {
			WriteError(w, r, http.StatusNotFound, "NOT_FOUND", err.Error())
			return
		}
		writeJSONStatus(w, http.StatusOK, probe)
	}
}

// mapAdminError translates a sentinel error into the canonical envelope.
func mapAdminError(w http.ResponseWriter, r *http.Request, err error) {
	if admin.IsValidation(err) {
		var v admin.ErrValidation
		if errors.As(err, &v) {
			WriteValidationError(w, r, []ValidationDetail{{Field: v.Field, Issue: v.Issue}})
			return
		}
	}
	if admin.IsEndpointRetired(err) {
		WriteError(w, r, http.StatusNotImplemented, "ENDPOINT_RETIRED", err.Error())
		return
	}
	if strings.Contains(err.Error(), "not registered") {
		WriteError(w, r, http.StatusNotFound, "NOT_FOUND", err.Error())
		return
	}
	if strings.Contains(err.Error(), "vendor does not advertise capability") {
		WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", err.Error())
		return
	}
	WriteError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
}

// adminVerifyHMAC is the httpapi-side shim that calls into the admin
// package's verifyHMAC. Kept in this package so handlers_admin.go does
// not need to import admin internals.
func adminVerifyHMAC(r *http.Request, secret []byte) bool {
	return adminVerifyHMACImpl(r, secret)
}
