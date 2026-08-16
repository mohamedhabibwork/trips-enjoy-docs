package events

import (
	"encoding/json"
	"log"
	"sync"
)

// StdoutPublisher is the in-memory dev Publisher. It writes one JSON
// line per published envelope to stdout (so docker-compose / kubectl logs
// see every event). The production Kafka writer is the follow-up PR
// (segmentio/kafka-go + transactional outbox).
type StdoutPublisher struct {
	mu       sync.Mutex
	producer string
	topics   TopicMap
}

// NewStdoutPublisher returns a StdoutPublisher tagged with the given
// producer name and topic map. producer is written into every envelope
// to satisfy EVENT_ARCHITECTURE.md ("Event Envelope").
func NewStdoutPublisher(producer string, topics TopicMap) *StdoutPublisher {
	return &StdoutPublisher{producer: producer, topics: topics}
}

// Publish serializes the envelope to JSON and writes one log line. The
// line shape mirrors the Kafka wire format (envelope fields at the top
// level) so a downstream consumer reading from stdout sees the same shape
// it would see from the broker.
func (p *StdoutPublisher) Publish(env Envelope) error {
	if env.Producer == "" {
		env.Producer = p.producer
	}
	encoded, err := json.Marshal(env)
	if err != nil {
		return err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	log.Println(string(encoded))
	return nil
}

// Close is a no-op for the stdout publisher; the interface exists so the
// production Kafka publisher can be dropped in without changing call sites.
func (p *StdoutPublisher) Close() error { return nil }

// TopicMap returns the topic map the publisher was constructed with so
// callers (e.g. the chain resolver) can derive a topic without holding
// the original config.
func (p *StdoutPublisher) TopicMap() TopicMap { return p.topics }
