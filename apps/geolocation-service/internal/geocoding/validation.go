package geocoding

import (
	"fmt"
	"strings"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// ValidationDetail describes a single field-level validation failure.
// It mirrors docs/services/file-service/internal/httpapi.ValidationDetail
// and is exported so httpapi can render the canonical envelope.
type ValidationDetail struct {
	Field string `json:"field"`
	Issue string `json:"issue"`
}

// ValidateGeocodeForward enforces SRS.md §9 (FR--001): address length
// 3..256, locale ∈ {en, ar, … configured}, region_city_id is a UUID.
func ValidateGeocodeForward(req provider.GeocodeRequest) []ValidationDetail {
	var details []ValidationDetail
	if l := len(strings.TrimSpace(req.Address)); l < 3 || l > 256 {
		details = append(details, ValidationDetail{Field: "address", Issue: "OUT_OF_RANGE"})
	}
	if !validLocale(req.Locale) {
		details = append(details, ValidationDetail{Field: "locale", Issue: "OUT_OF_RANGE"})
	}
	if req.RegionCityID != "" && !isUUIDish(req.RegionCityID) {
		details = append(details, ValidationDetail{Field: "region_city_id", Issue: "INVALID_UUID"})
	}
	return details
}

// ValidateGeocodeReverse enforces SRS.md §9 (FR--002).
func ValidateGeocodeReverse(req provider.ReverseRequest) []ValidationDetail {
	var details []ValidationDetail
	if req.Coordinate.Lat < -90 || req.Coordinate.Lat > 90 {
		details = append(details, ValidationDetail{Field: "lat", Issue: "OUT_OF_RANGE"})
	}
	if req.Coordinate.Lon < -180 || req.Coordinate.Lon > 180 {
		details = append(details, ValidationDetail{Field: "lon", Issue: "OUT_OF_RANGE"})
	}
	if !validLocale(req.Locale) {
		details = append(details, ValidationDetail{Field: "locale", Issue: "OUT_OF_RANGE"})
	}
	return details
}

// ValidateEta enforces SRS.md §9 (FR--003).
func ValidateEta(req provider.EtaRequest) []ValidationDetail {
	var details []ValidationDetail
	if len(req.Waypoints) > 5 {
		details = append(details, ValidationDetail{Field: "waypoints", Issue: "TOO_MANY"})
	}
	switch req.TrafficBucket {
	case "low", "medium", "high", "unknown":
	default:
		details = append(details, ValidationDetail{Field: "traffic_bucket", Issue: "OUT_OF_RANGE"})
	}
	if req.Origin.Lat < -90 || req.Origin.Lat > 90 {
		details = append(details, ValidationDetail{Field: "origin.lat", Issue: "OUT_OF_RANGE"})
	}
	if req.Destination.Lat < -90 || req.Destination.Lat > 90 {
		details = append(details, ValidationDetail{Field: "destination.lat", Issue: "OUT_OF_RANGE"})
	}
	return details
}

// ValidateRoute enforces SRS.md §9 (FR--004).
func ValidateRoute(req provider.RouteRequest) []ValidationDetail {
	var details []ValidationDetail
	if len(req.Waypoints) > 5 {
		details = append(details, ValidationDetail{Field: "waypoints", Issue: "TOO_MANY"})
	}
	if req.Alternatives != req.Alternatives { // bool zero-value is fine; here we accept either
		// no-op; boolean validity is implicit
	}
	if req.Geometry != "polyline" && req.Geometry != "geojson" {
		details = append(details, ValidationDetail{Field: "geometry", Issue: "OUT_OF_RANGE"})
	}
	return details
}

// validLocale reports whether the locale is in the supported set.
// The production wiring reads the configured set from
// configuration-service; the dev scaffold hard-codes {en, ar}.
func validLocale(locale string) bool {
	switch locale {
	case "en", "ar":
		return true
	}
	return false
}

// isUUIDish is a permissive UUID check (the production implementation
// uses google/uuid.Parse; the dev scaffold accepts the same shape).
func isUUIDish(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, r := range value {
		switch i {
		case 8, 13, 18, 23:
			if r != '-' {
				return false
			}
		default:
			if !((r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F')) {
				return false
			}
		}
	}
	return true
}

// JoinDetails renders a single-line summary for error envelope messages.
// Empty when there are no details.
func JoinDetails(details []ValidationDetail) string {
	if len(details) == 0 {
		return ""
	}
	parts := make([]string, 0, len(details))
	for _, d := range details {
		parts = append(parts, fmt.Sprintf("%s=%s", d.Field, d.Issue))
	}
	return strings.Join(parts, "; ")
}
