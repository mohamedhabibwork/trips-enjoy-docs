package mock

import (
	"math"
	"sync"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// Fixtures is the canonical city table the mock uses to label every
// coordinate with a city/country. It ships with 8 cities per README
// §17 (EU-WEST + US-EAST test cities); the production wiring replaces
// this with a live zone lookup (the `LastKnownCity` flow on
// geolocation-service (zones)).
type Fixtures struct {
	mu     sync.RWMutex
	cities []CityFixture
}

// CityFixture is one labelled city centroid.
type CityFixture struct {
	Name    string
	Country string
	Lat     float64
	Lon     float64
}

// DefaultFixtures returns the canonical dev fixtures.
func DefaultFixtures() *Fixtures {
	return &Fixtures{cities: canonicalCities}
}

// ReverseCity finds the closest city within ~100 km of coord. If none
// qualifies, returns "Unknown", "".
func (f *Fixtures) ReverseCity(coord provider.Coordinate) (city, country string) {
	f.mu.RLock()
	defer f.mu.RUnlock()
	best := -1
	bestMeters := math.MaxFloat64
	for i, c := range f.cities {
		m := approxMeters(coord.Lat, coord.Lon, c.Lat, c.Lon)
		if m < bestMeters {
			bestMeters = m
			best = i
		}
	}
	if best < 0 || bestMeters > 100_000 {
		return "Unknown", ""
	}
	return f.cities[best].Name, f.cities[best].Country
}

func approxMeters(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 6371000.0
	rad := func(f float64) float64 { return f * 0.0174533 }
	dLat := rad(lat2 - lat1)
	dLon := rad(lon2 - lon1)
	lat1r := rad(lat1)
	lat2r := rad(lat2)
	a := dLat*dLat + dLon*dLon*math.Cos(lat1r)*math.Cos(lat2r)
	return 2 * r * math.Sqrt(a)
}

// canonicalCities is the dev fixture table. Eight cities, four per the
// EU-WEST / US-EAST split called out in README §17.
var canonicalCities = []CityFixture{
	{Name: "London", Country: "GB", Lat: 51.5074, Lon: -0.1278},
	{Name: "Paris", Country: "FR", Lat: 48.8566, Lon: 2.3522},
	{Name: "Berlin", Country: "DE", Lat: 52.5200, Lon: 13.4050},
	{Name: "Dublin", Country: "IE", Lat: 53.3498, Lon: -6.2603},
	{Name: "New York", Country: "US", Lat: 40.7128, Lon: -74.0060},
	{Name: "San Francisco", Country: "US", Lat: 37.7749, Lon: -122.4194},
	{Name: "Austin", Country: "US", Lat: 30.2672, Lon: -97.7431},
	{Name: "Toronto", Country: "CA", Lat: 43.6532, Lon: -79.3832},
}
