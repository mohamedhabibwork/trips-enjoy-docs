// Package events is the geolocation-service outbound event surface.
// Producers write to a Publisher (in-memory stdout for the dev scaffold;
// a segmentio/kafka-go writer when GEOLOCATION_SERVICE_KAFKA_BOOTSTRAP_SERVERS
// is set). The canonical envelope follows docs/architecture/EVENT_ARCHITECTURE.md.
package events

import "time"

// Envelope is the canonical event envelope per EVENT_ARCHITECTURE.md
// ("Event Envelope"). Every event the service publishes — including the
// five geolocation.* events — carries these fields plus an event-specific
// Data payload.
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

// Event names for the five geolocation.* events per INTEGRATION.md §3.
const (
	EventGeocodedV1         = "geolocation.geocoded.v1"
	EventEtaComputedV1      = "geolocation.eta.computed.v1"
	EventCacheInvalidatedV1 = "geolocation.cache.invalidated.v1"
	EventProviderChainV1    = "geolocation.provider_chain.changed.v1"
	EventProviderHealthV1   = "geolocation.provider_health.v1"
)

// TopicMap describes how each event name maps to a Kafka topic.
// Defaults match the values in INTEGRATION.md §3; deployments can
// override via GEOLOCATION_SERVICE_KAFKA_TOPIC_* environment variables.
type TopicMap struct {
	Geocoded         string
	EtaComputed      string
	CacheInvalidated string
	ProviderChain    string
	ProviderHealth   string
}

// TopicFor resolves the topic for an event name.
func (m TopicMap) TopicFor(eventName string) string {
	switch eventName {
	case EventGeocodedV1:
		return m.Geocoded
	case EventEtaComputedV1:
		return m.EtaComputed
	case EventCacheInvalidatedV1:
		return m.CacheInvalidated
	case EventProviderChainV1:
		return m.ProviderChain
	case EventProviderHealthV1:
		return m.ProviderHealth
	}
	return ""
}
