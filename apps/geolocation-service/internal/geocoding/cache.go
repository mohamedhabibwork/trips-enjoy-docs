package geocoding

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"sync"
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/provider"
)

// CacheKind identifies which cache the entry belongs to. The
// geocoding service maintains one per cache kind with its own TTL.
type CacheKind string

const (
	CacheGeocode CacheKind = "geocode"
	CacheEta     CacheKind = "eta"
	CacheRoute   CacheKind = "route"
)

// CacheEntry is the in-memory mirror of a row in geocode_cache /
// eta_cache / route_cache. The geocoding service writes one of these
// on a vendor success and reads it on the hot path.
type CacheEntry struct {
	CacheKey   string
	Kind       CacheKind
	VendorID   string
	Coordinate provider.Coordinate
	Address    provider.GeoAddress
	Eta        provider.EtaEstimate
	Route      provider.Route
	CreatedAt  time.Time
	ExpiresAt  time.Time
}

// Cache is the in-memory cache used by the geocoding service. It is
// bounded by the dev-scaffold's acceptable working-set size; the
// production implementation persists to PostgreSQL (geocode_cache /
// eta_cache / route_cache) and mirrors hot reads to Redis.
type Cache struct {
	mu      sync.RWMutex
	geocode map[string]*CacheEntry
	eta     map[string]*CacheEntry
	route   map[string]*CacheEntry
	now     func() time.Time
}

// NewCache returns an empty Cache. now is the time-source closure
// (tests override to make TTL deterministic).
func NewCache(now func() time.Time) *Cache {
	if now == nil {
		now = time.Now
	}
	return &Cache{
		geocode: map[string]*CacheEntry{},
		eta:     map[string]*CacheEntry{},
		route:   map[string]*CacheEntry{},
		now:     now,
	}
}

// LookupGeocode returns the cache entry whose key matches and whose
// TTL has not elapsed. The boolean is false when nothing matches.
func (c *Cache) LookupGeocode(key string) (*CacheEntry, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	entry, ok := c.geocode[key]
	if !ok {
		return nil, false
	}
	if c.now().After(entry.ExpiresAt) {
		return nil, false
	}
	return entry, true
}

// LookupEta returns the cache entry for an ETA lookup.
func (c *Cache) LookupEta(key string) (*CacheEntry, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	entry, ok := c.eta[key]
	if !ok || c.now().After(entry.ExpiresAt) {
		return nil, false
	}
	return entry, true
}

// LookupRoute returns the cache entry for a route lookup.
func (c *Cache) LookupRoute(key string) (*CacheEntry, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	entry, ok := c.route[key]
	if !ok || c.now().After(entry.ExpiresAt) {
		return nil, false
	}
	return entry, true
}

// PutGeocode writes a geocode cache entry with TTL.
func (c *Cache) PutGeocode(entry *CacheEntry, ttl time.Duration) {
	if entry == nil {
		return
	}
	if entry.CreatedAt.IsZero() {
		entry.CreatedAt = c.now().UTC()
	}
	entry.ExpiresAt = entry.CreatedAt.Add(ttl)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.geocode[entry.CacheKey] = entry
}

// PutEta writes an ETA cache entry with TTL.
func (c *Cache) PutEta(entry *CacheEntry, ttl time.Duration) {
	if entry == nil {
		return
	}
	if entry.CreatedAt.IsZero() {
		entry.CreatedAt = c.now().UTC()
	}
	entry.ExpiresAt = entry.CreatedAt.Add(ttl)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.eta[entry.CacheKey] = entry
}

// PutRoute writes a route cache entry with TTL.
func (c *Cache) PutRoute(entry *CacheEntry, ttl time.Duration) {
	if entry == nil {
		return
	}
	if entry.CreatedAt.IsZero() {
		entry.CreatedAt = c.now().UTC()
	}
	entry.ExpiresAt = entry.CreatedAt.Add(ttl)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.route[entry.CacheKey] = entry
}

// PurgeAll wipes the in-memory cache (best-effort; PG + Redis purges
// happen in the admin handler). Used by the cache invalidation job.
func (c *Cache) PurgeAll() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := len(c.geocode) + len(c.eta) + len(c.route)
	c.geocode = map[string]*CacheEntry{}
	c.eta = map[string]*CacheEntry{}
	c.route = map[string]*CacheEntry{}
	return n
}

// PurgeByQueryFingerprint removes entries whose query fingerprint
// matches. The dev scaffold uses Address.FormattedAddress as the
// fingerprint (a real implementation hashes the normalized query).
func (c *Cache) PurgeByQueryFingerprint(fp string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for k, e := range c.geocode {
		if strings.Contains(strings.ToLower(e.Address.FormattedAddress), strings.ToLower(fp)) {
			delete(c.geocode, k)
			n++
		}
	}
	return n
}

// GeocodeCacheKey is the BR--020 cache key: SHA-256 of
// (normalized address | locale | region_city_id).
func GeocodeCacheKey(address, locale, regionCityID string) string {
	return hashParts(strings.ToLower(strings.TrimSpace(address)), locale, regionCityID)
}

// ReverseCacheKey is the BR--021 cache key: SHA-256 of
// (rounded lat | rounded lon | locale).
func ReverseCacheKey(coordinate provider.Coordinate, locale string) string {
	round := func(f float64) string {
		// 6 decimal places ≈ 10 cm (BR--021).
		return strings.TrimRight(strings.TrimRight(
			float64ToString(f*1e6/1e6), "0"), ".")
	}
	return hashParts(round(coordinate.Lat), round(coordinate.Lon), locale)
}

// EtaCacheKey is the BR--022 cache key: SHA-256 of
// (origin_grid | dest_grid | traffic_bucket | hour_of_day).
func EtaCacheKey(req provider.EtaRequest) string {
	hour := -1
	if req.DepartureTime != nil {
		hour = req.DepartureTime.UTC().Hour()
	}
	return hashParts(
		gridCell(req.Origin),
		gridCell(req.Destination),
		req.TrafficBucket,
		itoa(hour),
	)
}

// RouteCacheKey is the BR--023 cache key: SHA-256 of
// (origin_grid | dest_grid | hour_of_day).
func RouteCacheKey(req provider.RouteRequest) string {
	return hashParts(gridCell(req.Origin), gridCell(req.Destination), "0")
}

func gridCell(c provider.Coordinate) string {
	// ~10m grid at the equator.
	const step = 0.0001
	return float64ToString(float64(int64(c.Lat/step))*step) + ":" +
		float64ToString(float64(int64(c.Lon/step))*step)
}

func hashParts(parts ...string) string {
	h := sha256.New()
	for _, p := range parts {
		_, _ = h.Write([]byte(p))
		_, _ = h.Write([]byte{0})
	}
	return hex.EncodeToString(h.Sum(nil))
}

func float64ToString(f float64) string {
	// Avoid importing strconv for one helper; the platform uses
	// strconv everywhere else, but this package keeps that import
	// surface tiny.
	if f == 0 {
		return "0"
	}
	neg := f < 0
	if neg {
		f = -f
	}
	intPart := int64(f)
	frac := f - float64(intPart)
	var buf [32]byte
	pos := len(buf)
	// Fractional part — 6 digits.
	for i := 0; i < 6; i++ {
		frac *= 10
		d := int(frac)
		pos--
		buf[pos] = byte('0' + d)
		frac -= float64(d)
	}
	pos--
	buf[pos] = '.'
	// Integer part.
	if intPart == 0 {
		pos--
		buf[pos] = '0'
	} else {
		for intPart > 0 {
			pos--
			buf[pos] = byte('0' + int(intPart%10))
			intPart /= 10
		}
	}
	if neg {
		pos--
		buf[pos] = '-'
	}
	return string(buf[pos:])
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	neg := i < 0
	if neg {
		i = -i
	}
	var buf [16]byte
	pos := len(buf)
	for i > 0 {
		pos--
		buf[pos] = byte('0' + i%10)
		i /= 10
	}
	if neg {
		pos--
		buf[pos] = '-'
	}
	return string(buf[pos:])
}
