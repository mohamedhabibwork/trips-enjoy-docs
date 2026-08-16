package gateway

import (
	"encoding/json"
	"regexp"
	"testing"
)

var uuidV7Event = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func TestBuildAuditRequest(t *testing.T) {
	e := BuildAuditRequest("req-1", AuditRequestData{
		UserID:    "u-1",
		UserType:  "customer",
		Method:    "POST",
		Route:     "/v1/rides",
		Status:    201,
		LatencyMs: 142,
	})
	if e.EventID == "" || !uuidV7Event.MatchString(e.EventID) {
		t.Errorf("event id = %q", e.EventID)
	}
	if e.EventName != "audit.api.request.v1" {
		t.Errorf("event name = %q", e.EventName)
	}
	if e.CorrelationID != "req-1" {
		t.Errorf("correlation id = %q", e.CorrelationID)
	}
	body, err := json.Marshal(e)
	if err != nil {
		t.Fatal(err)
	}
	if !contains(string(body), `"event_name":"audit.api.request.v1"`) {
		t.Fatalf("missing event_name in JSON: %s", body)
	}
}

func TestBuildRateLimitExceeded(t *testing.T) {
	e := BuildRateLimitExceeded("req-2", RateLimitData{
		Route:             "/v1/rides",
		PrincipalType:     "token",
		PrincipalID:       "u-1",
		Limit:             100,
		WindowSeconds:     60,
		RetryAfterSeconds: 12,
	})
	if e.AggregateID != "route:/v1/rides" {
		t.Fatalf("aggregate id = %q", e.AggregateID)
	}
}

func TestBuildCircuitBreakerOpened(t *testing.T) {
	e := BuildCircuitBreakerOpened("req-3", "trip-service", "half_open", "open", 5)
	if e.AggregateID != "upstream:trip-service" {
		t.Fatalf("agg = %q", e.AggregateID)
	}
	body, _ := json.Marshal(e)
	if !contains(string(body), `"new_state":"open"`) {
		t.Fatalf("missing new_state: %s", body)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
