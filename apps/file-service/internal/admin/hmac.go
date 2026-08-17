package admin

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// verifyHMAC validates the X-Signature header for admin mutations.
// The expected header is "t=<unix>,v1=<hex>" where v1 =
// HMAC-SHA256(secret, "t=<unix>."). The signing key is the Go-side
// HMAC secret (cfg.HMACSecret); Go-side clients must use the same
// crypto/hmac construction. (openssl's `-hmac` flag uses a different
// key-derivation step and will NOT produce matching signatures; use
// the Go verifier on the client side.)
//
// When the secret is nil/empty (dev only) the function accepts
// unsigned requests so curl-based smoke tests work.
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
	// Reject signatures older than 5 minutes or more than 5 minutes
	// in the future (clock-skew tolerance).
	now := time.Now().Unix()
	if now-unix > 300 || unix-now > 300 {
		return false
	}
	return true
}
