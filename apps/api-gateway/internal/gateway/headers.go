// Package gateway — claim-to-X-User-* translator.
//
// Per docs/architecture/API_STANDARDS.md §7 and
// docs/services/api-gateway/INTEGRATION.md §1.1 the gateway
// converts JWT claims into a stable set of request headers so
// downstream services do not have to re-parse JWTs:
//
//	X-User-Id     := sub
//	X-User-Type   := user_type   (one of customer|driver|courier|merchant_staff|
//	                              restaurant_staff|support_agent|admin|
//	                              partner|service|anonymous)
//	X-Tenant-Id   := tenant_id   (omitempty)
//	X-Roles       := roles[]     joined by ","
//	X-Scopes      := scopes[]    joined by " "
//	X-Region      := region      (omitempty)
//	X-Device-Id   := device_id   (omitempty)
//
// Defense-in-depth: any client-supplied X-User-* (or X-Tenant-Id,
// X-Roles, X-Scopes, X-Region, X-Device-Id) header MUST be deleted
// before the upstream proxy adds the translated copy. The proxy
// enforces this in director().
//
// The translator is reversible (DecodeHeader is the inverse) so
// tests can assert header→claim symmetricity.
package gateway

import (
	"net/http"
	"strings"
)

// UserHeader names. Centralised to avoid typos and to make the
// delete-from-client step exhaustive.
var (
	HeaderUserID   = "X-User-Id"
	HeaderUserType = "X-User-Type"
	HeaderTenantID = "X-Tenant-Id"
	HeaderRoles    = "X-Roles"
	HeaderScopes   = "X-Scopes"
	HeaderRegion   = "X-Region"
	HeaderDeviceID = "X-Device-Id"
)

// userHeaders is the closed set of headers the gateway injects and
// trusts. Any header in this set arriving from the client is
// deleted.
var userHeaders = []string{
	HeaderUserID, HeaderUserType, HeaderTenantID,
	HeaderRoles, HeaderScopes, HeaderRegion, HeaderDeviceID,
}

// stripUserHeaders removes every header in userHeaders from h.
// Used before translation; the translator then writes fresh
// values.
func stripUserHeaders(h http.Header) {
	for _, k := range userHeaders {
		h.Del(k)
	}
}

// Translate writes the X-User-* headers onto the outbound request
// hdr, derived from claims. Existing values are overwritten; the
// proxy has already deleted any client-supplied copies before
// calling Translate.
func Translate(hdr http.Header, claims *Claims) {
	if claims == nil {
		return
	}
	if claims.Sub != "" {
		hdr.Set(HeaderUserID, claims.Sub)
	}
	if claims.UserType != "" {
		hdr.Set(HeaderUserType, claims.UserType)
	} else {
		// Per JWT contract every token carries a user_type;
		// downstream must not have to guess.
		hdr.Set(HeaderUserType, "service")
	}
	if claims.TenantID != "" {
		hdr.Set(HeaderTenantID, claims.TenantID)
	}
	if len(claims.Roles) > 0 {
		hdr.Set(HeaderRoles, strings.Join(claims.Roles, ","))
	}
	if len(claims.Scopes) > 0 {
		hdr.Set(HeaderScopes, strings.Join(claims.Scopes, " "))
	}
	if claims.Region != "" {
		hdr.Set(HeaderRegion, claims.Region)
	}
	if claims.DeviceID != "" {
		hdr.Set(HeaderDeviceID, claims.DeviceID)
	}
}

// AssertRoles returns nil when the caller's roles include at
// least one of the required roles. The matching is exact (no
// hierarchical inheritance at the gateway; downstream services
// enforce resource-level rules).
func AssertRoles(claims *Claims, required []string) bool {
	if len(required) == 0 {
		return true
	}
	if claims == nil || len(claims.Roles) == 0 {
		return false
	}
	have := make(map[string]struct{}, len(claims.Roles))
	for _, r := range claims.Roles {
		have[r] = struct{}{}
	}
	for _, r := range required {
		if _, ok := have[r]; ok {
			return true
		}
	}
	return false
}
