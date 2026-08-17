package events

import (
	"sync"
	"time"
)

// OutboxRow mirrors a row in the file.outbox table (canonical DDL ported
// from geolocation-service/ERD.md per PLATFORM_BASELINE.md §3). The
// dev scaffold keeps rows in memory; the production implementation
// persists via the same transactional outbox pattern documented in
// docs/architecture/EVENT_ARCHITECTURE.md.
type OutboxRow struct {
	ID            string
	AggregateType string
	AggregateID   string
	EventName     string
	Payload       []byte
	CreatedAt     time.Time
	PublishedAt   *time.Time
	Attempts      int
}

// Outbox is the in-memory transactional outbox. Producers insert rows
// in the same logical transaction that mutates state (Save → commit →
// Publish) and a separate poller drains the queue (the poller is wired
// in a follow-up PR).
type Outbox struct {
	mu   sync.Mutex
	rows []OutboxRow
}

// NewOutbox returns an empty in-memory outbox.
func NewOutbox() *Outbox { return &Outbox{} }

// Append inserts a row in the outbox.
func (o *Outbox) Append(row OutboxRow) {
	o.mu.Lock()
	defer o.mu.Unlock()
	row.Attempts = 0
	o.rows = append(o.rows, row)
}

// MarkPublished records that a row has been delivered to the broker.
// Rows older than 24h are pruned at startup to bound memory.
func (o *Outbox) MarkPublished(id string) {
	now := time.Now().UTC()
	o.mu.Lock()
	defer o.mu.Unlock()
	for i := range o.rows {
		if o.rows[i].ID == id && o.rows[i].PublishedAt == nil {
			o.rows[i].PublishedAt = &now
		}
	}
}

// Pending returns rows that have not yet been published.
func (o *Outbox) Pending() []OutboxRow {
	o.mu.Lock()
	defer o.mu.Unlock()
	out := make([]OutboxRow, 0, len(o.rows))
	for _, r := range o.rows {
		if r.PublishedAt == nil {
			out = append(out, r)
		}
	}
	return out
}
