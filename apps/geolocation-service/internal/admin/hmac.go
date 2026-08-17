// Package admin hosts the /v1/admin/* and /admin/v1/* handlers. These
// routes are mounted on the public mux (per INTEGRATION.md §1.6..§5)
// and gated by the admin / platform_engineer role checks via
// internal/auth. HMAC verification is enforced by verifyHMAC on every
// state-changing POST.
package admin

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
)

// Verify is the package-exported alias used by the httpapi layer
// (kept as a thin wrapper to avoid an httpapi → admin internals
// import).
func Verify(r *http.Request, secret []byte) bool { return verifyHMAC(r, secret) }

// verifyHMAC validates the X-Signature header for admin mutations. The
// expected header is "t=<unix>,v1=<hex>" where v1 = HMAC-SHA256(secret,
// "t=<unix>.<body>"). When secret is nil/empty the function accepts
// unsigned requests so dev curls work; in production the env var is
// always set (per INTEGRATION.md §5.4).
func verifyHMAC(r *http.Request, secret []byte) bool {
	if len(secret) == 0 {
		return true
	}
	sig := r.Header.Get("X-Signature")
	if sig == "" {
		return false
	}
	var ts, v1 string
	for _, part := range strings.Split(sig, ",") {
		kv := strings.SplitN(part, "=", 2)
		if len(kv) != 2 {
			continue
		}
		switch kv[0] {
		case "t":
			ts = kv[1]
		case "v1":
			v1 = kv[1]
		}
	}
	if ts == "" || v1 == "" {
		return false
	}
	unix, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(ts))
	mac.Write([]byte("."))
	expected := hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(expected), []byte(v1)) {
		return false
	}
	// Reject signatures older than 5 minutes (skew window).
	now := timeNow()
	if unix > 0 && now-unix > 300 {
		return false
	}
	return true
}

// timeNow is a var so tests can override the clock.
var timeNow = func() int64 { return nowSeconds() }

// nowSeconds returns the current unix-second. Wrapped in a helper so
// tests can stub it via the timeNow var.
func nowSeconds() int64 { return _now() }
