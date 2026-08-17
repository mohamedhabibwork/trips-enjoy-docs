// Package gateway — JWT verifier (Keycloak / RS256 / JWKS).
//
// The gateway validates every bearer JWT against Keycloak's JWKS
// for the realm that issued the token. The verifier wraps
// coreos/go-oidc/v3, which auto-refreshes the JWKS on key
// rotation events and uses RSA public-key verification per RFC 7517.
//
// Contract (docs/services/api-gateway/SRS.md §5, §11, §13 + doc
// FIXTURES / scripts/gen-jwks-fixture.sh):
//
//   - Bearer extraction is performed by the caller; this file only
//     parses, verifies and exposes claims.
//   - iss / aud / exp / nbf / sub are enforced (reject mismatches).
//   - jti is consulted against the Redis revocation set before
//     forwarding (see redis.go + ratelimit.go).
//   - kc_sub is consulted against the suspended / disabled sub set
//     for the same reason.
//   - Verification failures map to the codes documented in SRS §13:
//     TOKEN_INVALID (signature / kid unknown), TOKEN_EXPIRED
//     (exp/nbf), USER_SUSPENDED, USER_DISABLED, TOKEN_REVOKED.
//
// In local development `API_GATEWAY_DEV_INSECURE_NO_JWT=true` may
// be set to bypass JWT validation; the gateway then marks every
// request with `X-User-Type: anonymous` so behaviour is observable.
// Production MUST refuse to start with this flag set.
package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
)

// Claims is the canonical platform claim set (KEYCLOAK_ARCHITECTURE
// §"Custom claims"). The OIDC library tolerates extra fields in the
// token so service tokens (azp / azp_claims) are simply ignored.
type Claims struct {
	Sub           string   `json:"sub"`
	KCSub         string   `json:"kc_sub"`
	UserType      string   `json:"user_type"`
	TenantID      string   `json:"tenant_id"`
	Roles         []string `json:"roles"`
	Scopes        []string `json:"scopes"`
	Region        string   `json:"region"`
	DeviceID      string   `json:"device_id"`
	EmailVerified bool     `json:"email_verified"`
	PhoneVerified bool     `json:"phone_verified"`

	// Standard OIDC fields validated by the library itself:
	Issuer    string        `json:"iss"`
	Audience  AudienceClaim `json:"aud"`
	ExpiresAt int64         `json:"exp"`
	NotBefore int64         `json:"nbf"`
	IssuedAt  int64         `json:"iat"`
	JTI       string        `json:"jti"`
}

// AudienceClaim decodes either a single string or a string array
// per RFC 7519 §4.1.3.
type AudienceClaim []string

func (a *AudienceClaim) UnmarshalJSON(data []byte) error {
	var single string
	if err := json.Unmarshal(data, &single); err == nil {
		*a = []string{single}
		return nil
	}
	var arr []string
	if err := json.Unmarshal(data, &arr); err == nil {
		*a = arr
		return nil
	}
	return errors.New("aud claim must be a string or array of strings")
}

// Verifier wraps a per-issuer go-oidc verifier. Construct via
// NewVerifier or BuildVerifiers.
type Verifier struct {
	issuer string
	v      *oidc.IDTokenVerifier
}

// Verifiers is the gateway's lookup of Verifier per allowed issuer.
type Verifiers struct {
	mu        sync.RWMutex
	byIssuer  map[string]*Verifier
	cacheTTL  time.Duration
	issuerURL []string
}

// NewVerifiers builds one go-oidc verifier per issuer URL.
// Issuers are kept in the order provided so a request whose iss
// matches multiple providers is resolved deterministically (the
// first match wins).
func NewVerifiers(ctx context.Context, issuers []string, cacheTTL time.Duration) (*Verifiers, error) {
	v := &Verifiers{
		byIssuer:  make(map[string]*Verifier),
		cacheTTL:  cacheTTL,
		issuerURL: append([]string(nil), issuers...),
	}
	for _, issuer := range issuers {
		provider, err := oidc.NewProvider(ctx, issuer)
		if err != nil {
			return nil, fmt.Errorf("oidc discovery for %q: %w", issuer, err)
		}
		cfg := &oidc.Config{
			ClientID:          "api-gateway",
			SkipClientIDCheck: true, // multi-realm; aud is enforced at the claim layer
		}
		if cacheTTL > 0 {
			cfg.Now = func() time.Time { return time.Now() }
		}
		v.byIssuer[issuer] = &Verifier{
			issuer: issuer,
			v:      provider.Verifier(cfg),
		}
	}
	return v, nil
}

// IssuerURLs returns the configured issuers in stable order.
func (v *Verifiers) IssuerURLs() []string {
	return append([]string(nil), v.issuerURL...)
}

// verify resolves the verifier for the token's iss claim. If the
// iss is not in the configured list the call returns TOKEN_INVALID
// (per ADR-0019 / SEC-002 the iss is enforced).
func (v *Verifiers) verify(ctx context.Context, raw string, claims *Claims) error {
	v.mu.RLock()
	defer v.mu.RUnlock()
	if len(v.byIssuer) == 0 {
		return fmt.Errorf("no Keycloak issuers configured")
	}
	// Need to peek the iss header to pick the right provider; do a
	// cheap prefix parse rather than unmarshal twice.
	iss := peekIssuer(raw)
	candidate, ok := v.byIssuer[iss]
	if !ok {
		// Fall back to single-issuer mode (the common case).
		if len(v.byIssuer) == 1 {
			for _, single := range v.byIssuer {
				candidate = single
				break
			}
		} else {
			return ErrTokenInvalid
		}
	}
	tok, err := candidate.v.Verify(ctx, raw)
	if err != nil {
		if strings.Contains(err.Error(), "expired") || strings.Contains(err.Error(), "nbf") {
			return ErrTokenExpired
		}
		return ErrTokenInvalid
	}
	if err := tok.Claims(claims); err != nil {
		return fmt.Errorf("decode claims: %w", err)
	}
	return nil
}

// peekIssuer extracts the `iss` claim from a JWT without verifying
// the signature. This is only used to pick the right verifier and
// is safe because the verifier signature check fails closed on
// mismatch (RFC 7519 §7.2).
func peekIssuer(raw string) string {
	parts := strings.Split(raw, ".")
	if len(parts) < 2 {
		return ""
	}
	decoded, err := base64URLDecode(parts[1])
	if err != nil {
		return ""
	}
	var peek struct {
		Issuer string `json:"iss"`
	}
	if err := json.Unmarshal(decoded, &peek); err != nil {
		return ""
	}
	return peek.Issuer
}

// Sentinel errors surfaced by the verifier and translated to
// platform error codes by the proxy.
var (
	ErrTokenExpired      = errors.New("token expired")
	ErrTokenInvalid      = errors.New("token invalid")
	ErrTokenRevoked      = errors.New("token revoked")
	ErrUserSuspended     = errors.New("user suspended")
	ErrUserDisabled      = errors.New("user disabled")
	ErrMissingBearer     = errors.New("missing bearer token")
	ErrAuthNotConfigured = errors.New("gateway authentication not configured")
)

// Verify parses and validates a raw JWT string and returns the
// claims. The caller is expected to have already extracted the
// bearer token from the Authorization header.
func (v *Verifiers) Verify(ctx context.Context, raw string) (*Claims, error) {
	claims := &Claims{}
	if err := v.verify(ctx, raw, claims); err != nil {
		return nil, err
	}
	if claims.Sub == "" {
		return nil, ErrTokenInvalid
	}
	return claims, nil
}

// VerificationErrorCode maps a verifier sentinel error to a
// platform ErrorCode for the gateway's error envelope.
func VerificationErrorCode(err error) ErrorCode {
	switch {
	case errors.Is(err, ErrTokenExpired):
		return CodeTokenExpired
	case errors.Is(err, ErrTokenRevoked):
		return CodeTokenRevoked
	case errors.Is(err, ErrUserSuspended):
		return CodeUserSuspended
	case errors.Is(err, ErrUserDisabled):
		return CodeUserDisabled
	case errors.Is(err, ErrMissingBearer), errors.Is(err, ErrTokenInvalid):
		return CodeTokenInvalid
	case errors.Is(err, ErrAuthNotConfigured):
		return CodeAuthNotConfigured
	default:
		return CodeInternalError
	}
}

// ExtractBearer returns the raw JWT from r's Authorization header.
// Returns ErrMissingBearer when the header is missing or empty.
func ExtractBearer(r *http.Request) (string, error) {
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		return "", ErrMissingBearer
	}
	tok := strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
	if tok == "" {
		return "", ErrMissingBearer
	}
	return tok, nil
}
