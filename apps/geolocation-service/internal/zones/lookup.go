// Package zones owns the read path that the geolocation-service uses
// to answer "what city is this coordinate in?" — implemented today as
// an in-memory map seeded from the mock fixtures; the production
// implementation hits the geolocation-service (zones) sibling
// (INTEGRATION.md §2).
package zones

import (
	"context"
	"errors"
	"sync"

	"github.com/trips-enjoy/platform/geolocation-service/internal/db"
)

// ErrCityNotFound is the canonical 404 CITY_NOT_FOUND error per
// docs/services/geolocation-service/SRS.md §13.
var ErrCityNotFound = errors.New("coordinate is outside any known service zone")

// City is one entry from the city lookup. It mirrors the canonical
// shape INTEGRATION.md §1.5 documents.
type City struct {
	CityID      string
	Name        string
	CountryCode string
	Timezone    string
	Centroid    Coordinate
}

// Coordinate is the same shape as provider.Coordinate; kept as an
// alias so this package does not import internal/provider.
type Coordinate struct{ Lat, Lon float64 }

// Lookup is the read interface for the city lookup flow. The dev
// scaffold serves from the in-memory seed map; the production wiring
// calls GET /v1/cities/{city_id} on the geolocation-service (zones)
// sibling.
type Lookup struct {
	mu     sync.RWMutex
	byID   map[string]City
	cities []City
}

// NewLookup returns an empty Lookup. The caller seeds it via Add.
func NewLookup() *Lookup {
	return &Lookup{byID: map[string]City{}}
}

// Add registers one city in the lookup. IDs are minted with
// db.NewUUIDv7() to match the geocoding primary-key convention.
func (l *Lookup) Add(c City) {
	if c.CityID == "" {
		c.CityID = db.NewUUIDv7()
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.byID[c.CityID] = c
	l.cities = append(l.cities, c)
}

// LastKnownCity implements GET /v1/cities/lookup per INTEGRATION.md §1.5.
// It returns the City whose centroid is within 100 km of the supplied
// coordinate. The dev scaffold uses a coarse distance check; the
// production implementation delegates to PostGIS ST_Distance.
func (l *Lookup) LastKnownCity(_ context.Context, coord Coordinate) (City, error) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	best := -1
	bestMeters := 100_000.0 // 100 km threshold
	for i, c := range l.cities {
		meters := approxMeters(coord.Lat, coord.Lon, c.Centroid.Lat, c.Centroid.Lon)
		if meters < bestMeters {
			bestMeters = meters
			best = i
		}
	}
	if best < 0 {
		return City{}, ErrCityNotFound
	}
	return l.cities[best], nil
}

// ByID returns the City with the given UUID (used by the admin
// provider / chain tooling to resolve city metadata).
func (l *Lookup) ByID(id string) (City, bool) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	c, ok := l.byID[id]
	return c, ok
}

// List returns every known city (no pagination in the dev scaffold).
func (l *Lookup) List() []City {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]City, len(l.cities))
	copy(out, l.cities)
	return out
}

// approxMeters is the same haversine used in the mock provider — kept
// inline so this package does not import math directly.
func approxMeters(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 6371000.0
	rad := func(f float64) float64 { return f * 0.0174533 }
	dLat := rad(lat2 - lat1)
	dLon := rad(lon2 - lon1)
	lat1r := rad(lat1)
	lat2r := rad(lat2)
	a := dLat*dLat + dLon*dLon*cosImpl(lat1r)*cosImpl(lat2r)
	return 2 * r * sqrtImpl(a)
}

func cosImpl(x float64) float64  { return cosRef(x) }
func sqrtImpl(x float64) float64 { return sqrtRef(x) }
