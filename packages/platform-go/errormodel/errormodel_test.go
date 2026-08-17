package errormodel

import "testing"

func TestCodeHTTPStatus(t *testing.T) {
	cases := map[Code]int{
		CodeValidationFailed:                 400,
		CodeUnauthenticated:                  401,
		CodeForbidden:                        403,
		CodeNotFound:                         404,
		CodeConflict:                         409,
		CodeIdempotencyKeyReused:             422,
		CodeBusinessRuleViolation:            422,
		CodeRateLimited:                      429,
		CodeBadGateway:                       502,
		CodeCircuitOpen:                      503,
		CodeDependencyUnavailable:            503,
		CodeBulkheadFull:                     503,
		CodeDependencyTimeout:                504,
		CodeInternalError:                    500,
	}
	for code, want := range cases {
		if got := code.HTTPStatus(); got != want {
			t.Errorf("HTTPStatus(%s) = %d, want %d", code, got, want)
		}
	}
}

func TestEnvelopeShape(t *testing.T) {
	e := New(CodeNotFound, "id=abc", "/v1/payments/abc", "trace1", "span1")
	if e.Type != "https://platform.trips-enjoy.com/errors/not-found" {
		t.Errorf("Type = %q", e.Type)
	}
	if e.Title != "Not Found" {
		t.Errorf("Title = %q", e.Title)
	}
	if e.Status != 404 {
		t.Errorf("Status = %d", e.Status)
	}
	if e.Code != CodeNotFound {
		t.Errorf("Code = %q", e.Code)
	}
	if e.Detail != "id=abc" {
		t.Errorf("Detail = %q", e.Detail)
	}
	if e.Instance != "/v1/payments/abc" {
		t.Errorf("Instance = %q", e.Instance)
	}
}

func TestEnvelopeValidation(t *testing.T) {
	e := NewValidation(
		"validation failed",
		"/v1/orders",
		"trace1",
		"span1",
		[]FieldError{
			{Field: "amount", Message: "must be > 0", Code: "MIN_VALUE"},
		},
	)
	if e.Status != 400 {
		t.Errorf("Status = %d", e.Status)
	}
	if len(e.Errors) != 1 {
		t.Errorf("len(Errors) = %d", len(e.Errors))
	}
	if e.Errors[0].Field != "amount" {
		t.Errorf("FieldError.Field = %q", e.Errors[0].Field)
	}
}

func TestEnvelopeDownstream(t *testing.T) {
	ds := &Downstream{Service: "payment-service", Code: "CIRCUIT_OPEN", Status: 503, LatencyMs: 17, Attempt: 1}
	e := NewWithDownstream(CodeDependencyUnavailable, "upstream", "/v1/payments", "trace1", "span1", ds)
	if e.Downstream == nil {
		t.Fatal("Downstream is nil")
	}
	if e.Downstream.Service != "payment-service" {
		t.Errorf("Service = %q", e.Downstream.Service)
	}
}
