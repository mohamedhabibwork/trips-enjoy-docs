// Package gateway — OpenAPI aggregation helpers.
//
// The public /openapi.json aggregate is served from
// docs/services/api-gateway/INTEGRATION.md §1.2 (a CI-regenerated
// blob). Per-service specs are proxied through the gateway via
// /openapi/{service}.json so the developer portal can render them
// directly. This file exposes the small helpers router.go uses.
package gateway

import (
	"net/url"
	"strings"
)

// openAPIURL returns the upstream OpenAPI URL for the given
// service, or false if the service is unknown.
func openAPIURL(routes []Route, service string) (string, bool) {
	for _, route := range routes {
		if route.Service != service {
			continue
		}
		u, err := url.Parse(route.Upstream)
		if err != nil || u.Scheme == "" || u.Host == "" {
			return "", false
		}
		return strings.TrimRight(u.String(), "/") + "/openapi.json", true
	}
	return "", false
}

// matchRoute resolves the request path to a configured route. The
// rule is prefix-matching with a path-segment boundary check (so
// /v1/admin matches /v1/admin but not /v1/administrators).
func matchRoute(routes []Route, path string) (Route, bool) {
	for _, route := range routes {
		if path == route.Prefix {
			return route, true
		}
		if strings.HasPrefix(path, route.Prefix+"/") {
			return route, true
		}
	}
	return Route{}, false
}
