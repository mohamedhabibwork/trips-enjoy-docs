// Package events is the file-service outbound event surface. Producers
// write to a Publisher (in-memory stdout for the dev scaffold; a
// segmentio/kafka-go writer in the follow-up PR). The canonical envelope
// follows docs/architecture/EVENT_ARCHITECTURE.md.
package events

import "time"

// Envelope is the canonical event envelope per EVENT_ARCHITECTURE.md
// ("Event Envelope"). Every event the service publishes — including the
// four file.* events — carries these fields plus an event-specific Data
// payload.
type Envelope struct {
	EventID       string    `json:"event_id"`
	EventName     string    `json:"event_name"`
	SchemaVersion int       `json:"schema_version"`
	OccurredAt    time.Time `json:"occurred_at"`
	Producer      string    `json:"producer"`
	TenantID      string    `json:"tenant_id"`
	CorrelationID string    `json:"correlation_id"`
	CausationID   string    `json:"causation_id,omitempty"`
	AggregateType string    `json:"aggregate_type"`
	AggregateID   string    `json:"aggregate_id"`
	Data          any       `json:"data"`
}

// Publisher is the contract every transport implements. Implementations
// must be safe to call from many goroutines and must return an error
// when the broker rejects the write (the outbox poller retries on
// non-nil error per PLATFORM_BASELINE.md §3).
type Publisher interface {
	Publish(env Envelope) error
	Close() error
}

// Event names for the four file.* events per INTEGRATION.md §3.
const (
	EventFileUploadedV1 = "file.uploaded.v1"
	EventFileScannedV1  = "file.scanned.v1"
	EventFileDeletedV1  = "file.deleted.v1"
	EventFileMigratedV1 = "file.migrated.v1"
)

// TopicMap describes how each event name maps to a Kafka topic.
// Defaults match the values in INTEGRATION.md §3; deployments can
// override via FILE_SERVICE_KAFKA_TOPIC_FILE_* environment variables.
type TopicMap struct {
	Uploaded string
	Scanned  string
	Deleted  string
	Migrated string
}

// TopicFor resolves the topic for an event name.
func (m TopicMap) TopicFor(eventName string) string {
	switch eventName {
	case EventFileUploadedV1:
		return m.Uploaded
	case EventFileScannedV1:
		return m.Scanned
	case EventFileDeletedV1:
		return m.Deleted
	case EventFileMigratedV1:
		return m.Migrated
	}
	return ""
}
