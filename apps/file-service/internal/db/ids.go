package db

import "github.com/google/uuid"

// NewUUIDv7 returns a new UUIDv7 (time-ordered, k-sortable) per
// PLATFORM_BASELINE.md §7 + ADR-0015. Uses github.com/google/uuid
// `NewV7()` (RFC 9562 §5.7) — the Go canonical generator. The error
// is discarded because the underlying `crypto/rand.Read` failure is
// not recoverable in this process; matches api-gateway convention.
func NewUUIDv7() string {
	u, _ := uuid.NewV7()
	return u.String()
}

// IsUUID reports whether value parses as a UUID.
func IsUUID(value string) bool {
	_, err := uuid.Parse(value)
	return err == nil
}
