package httperr

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/trips-enjoy/platform-go/errormodel"
)

func TestWriteEnvelope(t *testing.T) {
	w := httptest.NewRecorder()
	e := errormodel.New(errormodel.CodeNotFound, "id=abc", "/v1/payments/abc", "trace1", "span1")
	Write(w, e)
	if w.Code != http.StatusNotFound {
		t.Errorf("Code = %d", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); ct != ContentType {
		t.Errorf("Content-Type = %q", ct)
	}
	var got errormodel.Envelope
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body unmarshal: %v", err)
	}
	if got.Code != errormodel.CodeNotFound {
		t.Errorf("Code = %q", got.Code)
	}
}

func TestWriteStatus(t *testing.T) {
	w := httptest.NewRecorder()
	e := errormodel.New(errormodel.CodeInternalError, "boom", "/v1/x", "t", "s")
	WriteStatus(w, http.StatusInternalServerError, e)
	if w.Code != http.StatusInternalServerError {
		t.Errorf("Code = %d", w.Code)
	}
}
