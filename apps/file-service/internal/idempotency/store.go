// Package idempotency implements the Idempotency-Key middleware contract
// from docs/architecture/API_STANDARDS.md §11. The dev scaffold keeps
// keys in an in-memory map with a 24h TTL; the production implementation
// persists to Redis (per INTEGRATION.md §15).
package idempotency

import (
	"crypto/sha256"
	"encoding/hex"
	"sync"
	"time"
)

// Entry is the cached response for a (client, key) pair.
type Entry struct {
	Key       string
	Hash      string
	Status    int
	Body      []byte
	StoredAt  time.Time
	ExpiresAt time.Time
}

// Store is the in-memory idempotency cache. Safe for concurrent use.
type Store struct {
	mu      sync.RWMutex
	entries map[string]Entry
	ttl     time.Duration
}

// NewStore returns an idempotency Store with the default 24h TTL.
func NewStore() *Store { return NewStoreWithTTL(24 * time.Hour) }

// NewStoreWithTTL returns a Store with a custom TTL (used by tests).
func NewStoreWithTTL(ttl time.Duration) *Store {
	return &Store{entries: map[string]Entry{}, ttl: ttl}
}

// HashBody returns a stable hex hash of the request body for use as the
// reuse-detector. Same key + same hash → cached response; same key +
// different hash → 422 IDEMPOTENCY_KEY_REUSED.
func HashBody(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

// Lookup returns the cached entry for (clientID, key) if present and
// not expired; (Entry{}, false) otherwise.
func (s *Store) Lookup(clientID, key, hash string) (Entry, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	entry, ok := s.entries[clientID+":"+key]
	if !ok || time.Now().After(entry.ExpiresAt) || entry.Hash != hash {
		return Entry{}, false
	}
	return entry, true
}

// Save records the cached response.
func (s *Store) Save(clientID, key, hash string, status int, body []byte) {
	now := time.Now().UTC()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[clientID+":"+key] = Entry{
		Key:       key,
		Hash:      hash,
		Status:    status,
		Body:      body,
		StoredAt:  now,
		ExpiresAt: now.Add(s.ttl),
	}
}

// purgeExpired deletes expired entries. Called by the janitor goroutine
// every minute so the map does not grow unbounded.
func (s *Store) purgeExpired() {
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	for k, v := range s.entries {
		if now.After(v.ExpiresAt) {
			delete(s.entries, k)
		}
	}
}
