package gateway

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
)

// httptestNewRequest is a tiny helper used across test files in
// this package. It returns an *http.Request with a non-nil Body
// (when body is non-empty) and the X-Request-Id header unset so the
// request-id middleware can be tested in isolation.
func httptestNewRequest(method, target, body string) *http.Request {
	var reader io.Reader
	if body != "" {
		reader = bytes.NewBufferString(body)
	}
	r := httptest.NewRequest(method, target, reader)
	return r
}
