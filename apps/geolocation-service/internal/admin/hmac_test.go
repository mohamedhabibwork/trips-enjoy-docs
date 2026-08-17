package admin

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestVerifyHMAC_AcceptsSignedRequest proves a well-formed
// X-Signature header validates against the configured secret.
func TestVerifyHMAC_AcceptsSignedRequest(t *testing.T) {
	secret := []byte("dev-secret")
	now := time.Now().Unix()
	body := []byte(`{"reason":"test"}`)
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(strconv.FormatInt(now, 10)))
	mac.Write([]byte("."))
	want := hex.EncodeToString(mac.Sum(nil))

	r, _ := http.NewRequest("POST", "/v1/admin/cache/purge", strings.NewReader(string(body)))
	r.Header.Set("X-Signature", "t="+strconv.FormatInt(now, 10)+",v1="+want)
	if !Verify(r, secret) {
		t.Fatalf("expected HMAC to validate")
	}
}

// TestVerifyHMAC_RejectsMismatchedSignature proves a tampered
// signature is rejected.
func TestVerifyHMAC_RejectsMismatchedSignature(t *testing.T) {
	secret := []byte("dev-secret")
	now := time.Now().Unix()
	r, _ := http.NewRequest("POST", "/v1/admin/cache/purge", nil)
	r.Header.Set("X-Signature", "t="+strconv.FormatInt(now, 10)+",v1=deadbeef")
	if Verify(r, secret) {
		t.Fatalf("expected HMAC mismatch to be rejected")
	}
}

// TestVerifyHMAC_AcceptsUnsignedInDevMode proves that when secret
// is empty (dev profile) the function accepts unsigned requests so
// curl-based local testing works (per INTEGRATION.md §5.4).
func TestVerifyHMAC_AcceptsUnsignedInDevMode(t *testing.T) {
	r, _ := http.NewRequest("POST", "/v1/admin/cache/purge", nil)
	if !Verify(r, nil) {
		t.Fatalf("expected unsigned request to pass when secret is nil (dev mode)")
	}
}
