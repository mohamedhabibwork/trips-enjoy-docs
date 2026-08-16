// Package gateway — base64url (RFC 4648 §5) helpers used by the
// JWT verifier.
package gateway

import (
	"encoding/base64"
	"errors"
)

// base64URLDecode decodes a base64url-encoded string. RFC 7515 §2
// uses base64url without padding; the function tolerates missing
// padding.
func base64URLDecode(s string) ([]byte, error) {
	if s == "" {
		return nil, errors.New("empty base64url string")
	}
	// Try padded first.
	if padded, err := base64.RawURLEncoding.DecodeString(s); err == nil {
		return padded, nil
	}
	// Fall back to std (handles values with padding).
	if padded, err := base64.URLEncoding.DecodeString(s); err == nil {
		return padded, nil
	}
	return nil, errors.New("invalid base64url")
}
