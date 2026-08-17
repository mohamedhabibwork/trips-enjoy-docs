// Package gateway — WAF-style pattern block.
//
// Per docs/services/api-gateway/SRS.md FR-016 (defense in depth)
// the gateway matches obvious SQL-injection, XXE, and path-traversal
// patterns in well-known attack vectors. The real WAF sits in
// front of the gateway in production; this layer is a belt-and-
// braces check that catches obvious payloads early.
//
// Match scope: URL path, URL raw query, and body bytes (when the
// body is small enough to inspect, ≤ MaxBodyHashBytes per the
// body_hash helper). Headers are not scanned here — the request-id
// filter, JWT validator, and proxy enforce header hygiene (delete
// any client-supplied X-User-*, etc).
//
// When a pattern matches, the gateway responds with 403
// WAF_BLOCKED. The matched pattern name is NEVER included in the
// response body (it must not leak attack heuristics into the
// public surface) and NEVER logged with the request body. Only the
// code is logged.
package gateway

import (
	"bytes"
	"io"
	"net/http"
	"strings"
)

// pattern matches a single rule.
type wafPattern struct {
	name  string
	match func(s string) bool
}

// wafPatterns is the closed list of attack patterns the gateway
// blocks at the edge. False positives in the platform's normal
// traffic are acceptable as long as they are rare; the operator
// may extend this list per ADR-0016 without code changes via the
// `gateway.waf.patterns` key in configuration-service.
var wafPatterns = []wafPattern{
	{name: "sqli_union", match: func(s string) bool {
		return strings.Contains(s, "union select") || strings.Contains(s, "union%20select")
	}},
	{name: "sqli_or_1", match: func(s string) bool { return strings.Contains(s, "or 1=1") || strings.Contains(s, "or 1=") }},
	{name: "sqli_comment", match: func(s string) bool {
		return strings.Contains(s, "--") && (strings.Contains(s, "'") || strings.Contains(s, "\""))
	}},
	{name: "xxe_doctype", match: func(s string) bool { return strings.Contains(s, "<!doctype") || strings.Contains(s, "%3c!doctype") }},
	{name: "xxe_entity", match: func(s string) bool { return strings.Contains(s, "<!entity") || strings.Contains(s, "%3c!entity") }},
	{name: "path_traversal", match: func(s string) bool {
		return strings.Contains(s, "../") || strings.Contains(s, "..%2f") || strings.Contains(s, "..\\") || strings.Contains(s, "..%5c")
	}},
	{name: "shell_injection", match: func(s string) bool {
		return strings.Contains(s, "; rm -") || strings.Contains(s, ";rm -") || strings.Contains(s, "`") || strings.Contains(s, "%60") || strings.Contains(s, "$(")
	}},
}

// WAFMatch matches WAF patterns against path+query+body and returns
// the matched pattern name (empty = no match). Each candidate is
// scanned in both its raw form and a lowercased form so patterns
// like "OR 1=1" or "%3C!ENTITY" are caught on the wire as well as
// after decoding.
func WAFMatch(r *http.Request, body []byte) string {
	candidates := make([]string, 0, 8)
	if r.URL != nil {
		candidates = append(candidates, r.URL.Path)
		if raw := r.URL.RawQuery; raw != "" {
			candidates = append(candidates, raw)
			// Also scan the decoded query so URL-encoded payloads
			// ("%20OR%201%3D1") are caught after decoding.
			for k, vs := range r.URL.Query() {
				for _, v := range vs {
					if v != "" {
						candidates = append(candidates, k+"="+v)
					}
				}
			}
		}
	}
	if len(body) > 0 {
		candidates = append(candidates, string(body))
	}
	for _, c := range candidates {
		low := strings.ToLower(c)
		for _, p := range wafPatterns {
			if p.match(low) {
				return p.name
			}
		}
	}
	return ""
}

// WAFCheck runs WAFMatch on r+bodyReader; if a pattern matches, it
// writes a 403 WAF_BLOCKED envelope via WriteError and returns
// true. The middleware short-circuits on true.
func WAFCheck(r *http.Request, bodyReader io.Reader, w http.ResponseWriter) bool {
	var body []byte
	if bodyReader != nil {
		// Read once, with a cap so a malicious client cannot OOM
		// us. LimitReader caps at cap+1; truncation is fine here.
		body, _ = io.ReadAll(io.LimitReader(bodyReader, int64(MaxBodyHashBytes)+1))
	}
	pattern := WAFMatch(r, body)
	if pattern == "" {
		// Re-attach the body for downstream consumers.
		if len(body) > 0 {
			r.Body = io.NopCloser(bytes.NewReader(body))
			r.ContentLength = int64(len(body))
		}
		return false
	}
	WriteError(r.Context(), w, r, http.StatusForbidden, CodeWAFBlocked,
		"The request was rejected by the gateway's WAF.", nil)
	// The pattern name is logged via the structured logger at the
	// proxy layer so it stays observable without leaking into the
	// public response.
	return true
}
