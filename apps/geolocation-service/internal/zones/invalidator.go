package zones

import (
	"sync"
	"time"

	"github.com/trips-enjoy/platform/geolocation-service/internal/db"
)

// ZoneState mirrors the rows of geolocation.zone_invalidation_state
// (ERD.md §3.5). The dev scaffold keeps the polygon + bbox in memory;
// the production wiring persists to PostGIS.
type ZoneState struct {
	ZoneID         string
	CityID         string
	Polygon        []Point
	BBox           BBox
	PolygonVersion int
	UpdatedAt      time.Time
}

// Point is the 2-D vertex used for zone polygons in the dev scaffold.
type Point struct{ Lat, Lon float64 }

// BBox is a 2-D bounding box.
type BBox struct {
	MinLat, MinLon, MaxLat, MaxLon float64
}

// InboxEntry mirrors a row in geolocation.inbox (ERD.md §3.7).
type InboxEntry struct {
	EventID    string
	Consumer   string
	Topic      string
	ReceivedAt time.Time
}

// Invalidator handles zone.updated.v1 events per INTEGRATION.md §6.1
// and the zone-update-invalidator flow described in WORKFLOWS.md §2.5.
// The dev scaffold stores rows in memory; the production implementation
// writes to PostgreSQL.
type Invalidator struct {
	mu     sync.Mutex
	zones  map[string]ZoneState
	inbox  []InboxEntry
	purges int
}

// NewInvalidator returns an empty invalidator.
func NewInvalidator() *Invalidator {
	return &Invalidator{
		zones: map[string]ZoneState{},
	}
}

// HandleZoneUpdated is the inbound handler for `zone.updated.v1`. It
// upserts the row in zone_invalidation_state and emits
// geolocation.cache.invalidated.v1 (the publisher is wired in the
// geocoding service — the dev scaffold writes the side-effect counts
// only). The invalidation job that DELETEs cache rows whose coordinate
// intersects the polygon is a follow-up PR (real PostGIS query).
func (i *Invalidator) HandleZoneUpdated(eventID, topic string, state ZoneState) {
	if eventID == "" {
		eventID = db.NewUUIDv7()
	}
	state.UpdatedAt = time.Now().UTC()
	i.mu.Lock()
	defer i.mu.Unlock()
	i.zones[state.ZoneID] = state
	i.inbox = append(i.inbox, InboxEntry{
		EventID:    eventID,
		Consumer:   "zone-update-invalidator",
		Topic:      topic,
		ReceivedAt: time.Now().UTC(),
	})
	i.purges++
}

// Zone returns the cached polygon for zoneID.
func (i *Invalidator) Zone(zoneID string) (ZoneState, bool) {
	i.mu.Lock()
	defer i.mu.Unlock()
	z, ok := i.zones[zoneID]
	return z, ok
}

// Zones returns every cached zone (no pagination in the dev scaffold).
func (i *Invalidator) Zones() []ZoneState {
	i.mu.Lock()
	defer i.mu.Unlock()
	out := make([]ZoneState, 0, len(i.zones))
	for _, z := range i.zones {
		out = append(out, z)
	}
	return out
}

// Purges returns the count of zone-driven purges since process start.
func (i *Invalidator) Purges() int {
	i.mu.Lock()
	defer i.mu.Unlock()
	return i.purges
}
