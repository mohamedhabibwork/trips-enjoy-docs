package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"

	"github.com/trips-enjoy/platform/geolocation-service/internal/chain"
	"github.com/trips-enjoy/platform/geolocation-service/internal/geocoding"
	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
	"github.com/trips-enjoy/platform/geolocation-service/internal/zones"
)

// geocodeForwardHandler handles POST /v1/geocodes.
func geocodeForwardHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var req provider.GeocodeRequest
		if err := json.Unmarshal(body, &req); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		if req.Locale == "" {
			req.Locale = "en"
		}
		region := resolveRegion(r, req.RegionCityID, deps)
		resp, err := deps.Geocoding.GeocodeForward(r.Context(), region, req)
		if err != nil {
			mapGeocodingError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, resp)
	}
}

// geocodeReverseHandler handles GET /v1/geocodes/reverse.
func geocodeReverseHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		lat, err := parseFloatQuery(r, "lat")
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "lat missing or invalid")
			return
		}
		lon, err := parseFloatQuery(r, "lon")
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "lon missing or invalid")
			return
		}
		locale := r.URL.Query().Get("locale")
		if locale == "" {
			locale = "en"
		}
		approximate := r.URL.Query().Get("approximate") == "true"
		req := provider.ReverseRequest{
			Coordinate:  provider.Coordinate{Lat: lat, Lon: lon},
			Locale:      locale,
			Approximate: approximate,
		}
		region := resolveRegion(r, "", deps)
		resp, err := deps.Geocoding.GeocodeReverse(r.Context(), region, req)
		if err != nil {
			mapGeocodingError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, resp)
	}
}

// etaHandler handles POST /v1/etas.
func etaHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var req provider.EtaRequest
		if err := json.Unmarshal(body, &req); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		if req.TrafficBucket == "" {
			req.TrafficBucket = "unknown"
		}
		region := resolveRegion(r, "", deps)
		resp, err := deps.Geocoding.Eta(r.Context(), region, req)
		if err != nil {
			mapGeocodingError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, resp)
	}
}

// routeHandler handles POST /v1/routes.
func routeHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "could not read request body")
			return
		}
		var req provider.RouteRequest
		if err := json.Unmarshal(body, &req); err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "invalid JSON body")
			return
		}
		if req.Geometry == "" {
			req.Geometry = "polyline"
		}
		region := resolveRegion(r, "", deps)
		resp, err := deps.Geocoding.Route(r.Context(), region, req)
		if err != nil {
			mapGeocodingError(w, r, err)
			return
		}
		writeJSONStatus(w, http.StatusOK, resp)
	}
}

// citiesLookupHandler handles GET /v1/cities/lookup.
func citiesLookupHandler(deps Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		lat, err := parseFloatQuery(r, "lat")
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "lat missing or invalid")
			return
		}
		lon, err := parseFloatQuery(r, "lon")
		if err != nil {
			WriteError(w, r, http.StatusBadRequest, "VALIDATION_FAILED", "lon missing or invalid")
			return
		}
		city, err := deps.Zones.LastKnownCity(r.Context(), zones.Coordinate{Lat: lat, Lon: lon})
		if err != nil {
			if errors.Is(err, zones.ErrCityNotFound) {
				WriteError(w, r, http.StatusNotFound, "CITY_NOT_FOUND", "coordinate is outside any known service zone")
				return
			}
			WriteError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
			return
		}
		writeJSONStatus(w, http.StatusOK, geocoding.CityLookupResponse{
			CityID:      city.CityID,
			Name:        city.Name,
			CountryCode: city.CountryCode,
			Timezone:    city.Timezone,
			OccurredAt:  timeNow(),
		})
	}
}

// mapGeocodingError translates a service-layer error into the canonical
// envelope per SRS.md §13.
func mapGeocodingError(w http.ResponseWriter, r *http.Request, err error) {
	if geocoding.IsValidation(err) {
		var v geocoding.ErrValidation
		if errors.As(err, &v) {
			details := make([]ValidationDetail, 0, len(v.Details))
			for _, d := range v.Details {
				details = append(details, ValidationDetail{Field: d.Field, Issue: d.Issue})
			}
			WriteValidationError(w, r, details)
			return
		}
	}
	if errors.Is(err, chain.ErrCircuitOpen) {
		WriteError(w, r, http.StatusServiceUnavailable, "CIRCUIT_OPEN", "every provider in the chain is unavailable")
		return
	}
	if errors.Is(err, provider.ErrRegionUnsupported) {
		WriteError(w, r, http.StatusUnprocessableEntity, "ADDRESS_UNSUPPORTED_REGION", "address in region not served by any chain member")
		return
	}
	if errors.Is(err, provider.ErrTimeout) {
		WriteError(w, r, http.StatusGatewayTimeout, "DEPENDENCY_TIMEOUT", "vendor call timed out after retries")
		return
	}
	if errors.Is(err, provider.ErrNotConfigured) {
		WriteError(w, r, http.StatusServiceUnavailable, "CIRCUIT_OPEN", "no viable provider in the chain (all unconfigured)")
		return
	}
	WriteError(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", err.Error())
}

// resolveRegion picks the chain region for a request. Order of
// precedence: explicit city_id → Accept-Language country → "default"
// (per README.md §4.5 step 1).
func resolveRegion(r *http.Request, cityID string, deps Deps) chain.Region {
	if cityID != "" {
		return chain.Region("city:" + cityID)
	}
	if country := countryFromAcceptLanguage(r.Header.Get("Accept-Language")); country != "" {
		return chain.Region("country:" + country)
	}
	return "default"
}

func countryFromAcceptLanguage(value string) string {
	// Naive parse: take the first comma-delimited language, drop the
	// q-value / weight suffix, and extract the region suffix (after
	// the hyphen). The production wiring uses golang.org/x/text.
	for i := 0; i < len(value); i++ {
		if value[i] == ',' || value[i] == ';' {
			value = value[:i]
			break
		}
	}
	for i := len(value) - 1; i >= 0; i-- {
		if value[i] == '-' {
			return value[i+1:]
		}
	}
	return ""
}

func parseFloatQuery(r *http.Request, name string) (float64, error) {
	value := r.URL.Query().Get(name)
	if value == "" {
		return 0, errors.New("missing query parameter")
	}
	return strconv.ParseFloat(value, 64)
}

func writeJSONStatus(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// chiURLParam is a thin wrapper so handlers can use chi.URLParam
// without importing chi across the package boundary.
func chiURLParam(r *http.Request, key string) string { return chi.URLParam(r, key) }

// timeNow is forwarded via the httpapi package to avoid leaking the
// std-lib time import into multiple files.
var timeNow = func() (t timeT) { return timeTNow() }

// timeT is an alias so the httpapi package can hide the time import.
type timeT = timeAlias

// timeAlias is the same as time.Time; kept in a separate file so
// handlers_geocode.go doesn't need to import time.
type timeAlias = timeTimeImpl
