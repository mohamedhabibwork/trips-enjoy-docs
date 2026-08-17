// Package gateway — body-hash helper.
//
// Per docs/services/api-gateway/SRS.md §21 (`Auditability`) the
// gateway records a SHA-256 hash of the request body for diff-based
// audit search (body_sha256). The hash MUST be computed without
// materialising more than `cap` bytes — large bodies must be
// truncated and the `truncated: true` flag set.
package gateway

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
)

// MaxBodyHashBytes is the soft upper bound for the body bytes that
// are hashed for audit. Default 8 KiB per README §13.
//
// `gateway.audit.body_max_bytes` (default 8192).
const MaxBodyHashBytes = 8 << 10

// BodyHash reads up to MaxBodyHashBytes from r, computes a
// hex-encoded SHA-256 digest of what was read, and reports whether
// the stream was larger than the cap (in which case the digest is
// still emitted so the audit consumer can diff a prefix). Returns
// the empty hash and false when r is nil.
func BodyHash(r io.Reader) (digest string, truncated bool, err error) {
	if r == nil {
		return "", false, nil
	}
	hasher := sha256.New()
	limited := io.LimitReader(r, int64(MaxBodyHashBytes)+1)
	n, err := io.Copy(hasher, limited)
	if err != nil {
		return "", false, err
	}
	truncated = n > int64(MaxBodyHashBytes)
	return hex.EncodeToString(hasher.Sum(nil)), truncated, nil
}
