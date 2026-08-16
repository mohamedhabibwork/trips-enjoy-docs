// Package jwtauth provides the Keycloak JWT verification helper
// shared by Go services. Mirrors the `platform-spring-boot-security`
// module's JwtRoleConverter.
//
// The canonical contract:
//
//  1. Verify the JWT signature against the Keycloak JWKS endpoint.
//  2. Map `realm_access.roles[]` to `ROLE_<UPPER>`.
//  3. Map `resource_access.<client>.roles[]` to `ROLE_<CLIENT>_<UPPER>`.
//  4. Map `scope` (or `scp`) to `SCOPE_<lower>`.
//  5. Check expiration.
//
// The verifier is created once at startup and reused for every request.
package jwtauth

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
)

// Claims is the projected view of the JWT used by every consumer.
type Claims struct {
	Subject    string
	Username   string
	Email      string
	Roles      []string
	Scopes     []string
	ExpiresAt  time.Time
	Issuer     string
	Audience   []string
}

// HasRole returns true if the claims carry the named realm role.
func (c Claims) HasRole(name string) bool {
	for _, r := range c.Roles {
		if r == name {
			return true
		}
	}
	return false
}

// HasAnyRole returns true if the claims carry any of the named roles.
func (c Claims) HasAnyRole(names ...string) bool {
	for _, n := range names {
		if c.HasRole(n) {
			return true
		}
	}
	return false
}

// Verifier wraps the OIDC verifier with platform-specific claim
// projection.
type Verifier struct {
	v *oidc.IDTokenVerifier
}

// New creates a Verifier pointed at the given Keycloak issuer URL
// and the expected audience. The JWKS endpoint is fetched from the
// issuer's `.well-known/openid-configuration`.
func New(ctx context.Context, issuerURL, clientID string) (*Verifier, error) {
	provider, err := oidc.NewProvider(ctx, issuerURL)
	if err != nil {
		return nil, fmt.Errorf("jwtauth: provider: %w", err)
	}
	v := provider.Verifier(&oidc.Config{ClientID: clientID})
	return &Verifier{v: v}, nil
}

// Verify parses the raw token and returns the projected claims.
func (v *Verifier) Verify(ctx context.Context, rawToken string) (*Claims, error) {
	idt, err := v.v.Verify(ctx, rawToken)
	if err != nil {
		return nil, fmt.Errorf("jwtauth: verify: %w", err)
	}
	var raw struct {
		Sub      string `json:"sub"`
		Email    string `json:"email"`
		Username string `json:"preferred_username"`
		Aud      []string `json:"aud"`
		Scope    string `json:"scope"`
		Scp      string `json:"scp"`
		RealmAccess struct {
			Roles []string `json:"roles"`
		} `json:"realm_access"`
		ResourceAccess map[string]struct {
			Roles []string `json:"roles"`
		} `json:"resource_access"`
	}
	if err := idt.Claims(&raw); err != nil {
		return nil, fmt.Errorf("jwtauth: claims: %w", err)
	}
	c := &Claims{
		Subject:   raw.Sub,
		Username:  raw.Username,
		Email:     raw.Email,
		Roles:     raw.RealmAccess.Roles,
		Audience:  raw.Aud,
		ExpiresAt: idt.Expiry,
		Issuer:    idt.Issuer,
	}
	// scopes
	if raw.Scope != "" {
		c.Scopes = tokens(raw.Scope)
	} else if raw.Scp != "" {
		c.Scopes = tokens(raw.Scp)
	}
	// resource_access.<client>.roles -> "<CLIENT>_<ROLE>"
	for client, access := range raw.ResourceAccess {
		for _, role := range access.Roles {
			c.Roles = append(c.Roles, fmt.Sprintf("%s_%s", strings.ToUpper(client), strings.ToUpper(role)))
		}
	}
	return c, nil
}

func tokens(s string) []string {
	out := []string{}
	for _, t := range strings.Fields(s) {
		if t != "" {
			out = append(out, t)
		}
	}
	return out
}

// ErrInvalidToken is returned when the raw token is empty or unparseable.
var ErrInvalidToken = errors.New("jwtauth: empty token")
