// Package gateway — public aggregated downstream-health endpoint.
//
//	GET /healthz/downstream
//
// Returns one entry per distinct downstream service in the gateway's
// route table. Each entry combines:
//
//   - a fresh HTTP probe against <upstream>/health (the per-service
//     liveness endpoint per docs/architecture/OBSERVABILITY.md §"Health,
//     Readiness, Liveness"; liveness is intentionally side-effect-free
//     so the aggregator does not recursively probe dependencies);
//   - the current sony/gobreaker circuit state for that upstream,
//     reported via CircuitRegistry (read-only).
//
// The endpoint is PUBLIC on the gateway's public port (8080) — no
// bearer token, no role check, no audit emission. Abuse mitigation:
// the body carries only summary state (UP/DOWN + service names +
// latencies); the existing gateway:blocks:ip:* Redis blocklist still
// applies; linkerd mTLS protects in-cluster callers.
//
// Per-service probes BYPASS the circuit breaker (use a fresh
// *http.Client with a short timeout) so the operator gets a
// ground-truth snapshot even while a breaker is open. Breaker state
// is reported separately so the operator sees both signals.
//
// The handler always returns HTTP 200 when the aggregator itself
// works — per-service failures live inside the body as DOWN entries
// with a `downstream` block. RFC 7807 envelopes only fire for
// aggregator-level failures (snapshot load failure, JSON marshal
// failure) or query-param parse errors.
package gateway

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/sony/gobreaker"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// DownstreamHealthResponse is the body returned by /healthz/downstream.
type DownstreamHealthResponse struct {
	Status         string                 `json:"status"`     // UP | DEGRADED | DOWN | SKIPPED_ONLY
	CheckedAt      string                 `json:"checked_at"` // RFC3339 UTC
	CorrelationID  string                 `json:"correlation_id"`
	ConfigVersion  int64                  `json:"config_version"`
	ProbeTimeoutMs int64                  `json:"probe_timeout_ms"`
	Totals         DownstreamHealthTotals `json:"totals"`
	Services       []ServiceHealth        `json:"services"`
}

// DownstreamHealthTotals is the per-status summary across services.
type DownstreamHealthTotals struct {
	OK       int `json:"ok"`
	Degraded int `json:"degraded"`
	Down     int `json:"down"`
	Skipped  int `json:"skipped"`
	Total    int `json:"total"`
}

// ServiceHealth is one entry in the response's `services` array.
type ServiceHealth struct {
	Service      string      `json:"service"`
	Upstream     string      `json:"upstream"`
	Status       string      `json:"status"` // UP | DOWN | DEGRADED | SKIPPED
	LatencyMs    int64       `json:"latency_ms"`
	CheckedAt    string      `json:"checked_at"`
	Endpoint     string      `json:"endpoint"`      // the path probed (always "/health")
	CircuitState string      `json:"circuit_state"` // closed | half-open | open
	HTTPStatus   int         `json:"http_status"`   // 0 if no HTTP response (timeout / conn refused)
	Downstream   *Downstream `json:"downstream,omitempty"`
	Reason       string      `json:"reason,omitempty"` // populated for SKIPPED
}

// downstreamHealthOptions is the parsed query-string input to the
// aggregator. Built by downstreamHealthHandler; consumed by
// aggregateDownstreamHealth.
type downstreamHealthOptions struct {
	serviceFilter string // empty = all
	timeout       time.Duration
	parallelism   int
	nowFn         func() time.Time // overridden in tests
}

// downstreamHealthHandler returns the public /healthz/downstream
// http.HandlerFunc. Bound on the public mux (port 8080).
func downstreamHealthHandler(d RouterDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, span := d.Trace.Start(r.Context(), "gateway.healthz.downstream",
			trace.WithSpanKind(trace.SpanKindServer),
		)
		defer span.End()

		opts, err := parseDownstreamHealthOptions(r, d.Snapshots, defaultDownstreamProbeTimeout, defaultDownstreamProbeParallel)
		if err != nil {
			WriteError(ctx, w, r, http.StatusBadRequest, CodeValidationFailed, err.Error(), nil)
			return
		}

		snap := d.Snapshots.Load()
		if snap == nil {
			WriteError(ctx, w, r, http.StatusServiceUnavailable, CodeServiceUnavailable,
				"Gateway has not loaded its config.", nil)
			return
		}

		routes := distinctServices(snap.Routes, opts.serviceFilter)
		span.SetAttributes(
			attribute.Int("downstream.services_total", len(routes)),
			attribute.String("downstream.service_filter", opts.serviceFilter),
			attribute.Int64("downstream.probe_timeout_ms", opts.timeout.Milliseconds()),
			attribute.Int("downstream.parallelism", opts.parallelism),
		)

		client := &http.Client{
			Timeout: opts.timeout + 250*time.Millisecond, // a little slack over the per-request deadline
		}
		resp := aggregateDownstreamHealth(ctx, aggregateDownstreamHealthDeps{
			Routes:        routes,
			Circuits:      d.Circuits,
			Client:        client,
			Timeout:       opts.timeout,
			Parallelism:   opts.parallelism,
			CorrelationID: RequestID(r.Context()),
			ConfigVersion: snap.Version,
			Tracer:        d.Trace,
			nowFn:         opts.nowFn,
		})

		span.SetAttributes(
			attribute.Int("downstream.totals.ok", resp.Totals.OK),
			attribute.Int("downstream.totals.degraded", resp.Totals.Degraded),
			attribute.Int("downstream.totals.down", resp.Totals.Down),
			attribute.Int("downstream.totals.skipped", resp.Totals.Skipped),
		)

		body, err := json.Marshal(resp)
		if err != nil {
			WriteError(ctx, w, r, http.StatusInternalServerError, CodeInternalError,
				"Failed to encode aggregated health response.", nil)
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		// The aggregator always returns 200 unless the gateway itself
		// is broken. Per-service DOWN entries live inside the body.
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(body)
	}
}

// parseDownstreamHealthOptions decodes the query string into a typed
// options struct with clamps applied. Errors are returned with a
// caller-friendly message for the 400 envelope.
func parseDownstreamHealthOptions(r *http.Request, _ *SnapshotStore, defaultTimeout time.Duration, defaultParallelism int) (downstreamHealthOptions, error) {
	q := r.URL.Query()
	opts := downstreamHealthOptions{
		serviceFilter: strings.TrimSpace(q.Get("service")),
		timeout:       defaultTimeout,
		parallelism:   defaultParallelism,
		nowFn:         func() time.Time { return time.Now().UTC() },
	}
	if v := q.Get("timeout"); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return opts, &queryParseError{Field: "timeout", Value: v}
		}
		opts.timeout = d
	}
	if opts.timeout < 100*time.Millisecond {
		opts.timeout = 100 * time.Millisecond
	}
	if opts.timeout > 5*time.Second {
		opts.timeout = 5 * time.Second
	}
	if v := q.Get("parallelism"); v != "" {
		var n int
		if _, err := fmtParseInt(v, &n); err != nil || n < 1 {
			return opts, &queryParseError{Field: "parallelism", Value: v}
		}
		opts.parallelism = n
	}
	if opts.parallelism < 1 {
		opts.parallelism = 1
	}
	if opts.parallelism > 32 {
		opts.parallelism = 32
	}
	return opts, nil
}

type queryParseError struct {
	Field string
	Value string
}

func (e *queryParseError) Error() string {
	return "invalid query parameter " + e.Field + "=" + e.Value
}

// fmtParseInt is a tiny indirection so the test can swap the parser.
var fmtParseInt = func(s string, dst *int) (int, error) {
	var n int
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, &parseIntError{s: s}
		}
		n = n*10 + int(c-'0')
	}
	*dst = n
	return n, nil
}

type parseIntError struct{ s string }

func (e *parseIntError) Error() string { return "not an integer: " + e.s }

// distinctServices dedups the route table on `Service` (keep first
// occurrence; the table itself is ordered with public-route rows
// before admin routes, so the first match is the canonical public
// upstream). If filter is non-empty, only routes whose Service
// matches are returned; if filter matches nothing, a single SKIPPED
// placeholder is returned via an empty upstream, which the
// aggregator converts into a SKIPPED entry.
func distinctServices(routes []Route, filter string) []Route {
	seen := map[string]bool{}
	out := make([]Route, 0, len(routes))
	for _, r := range routes {
		if r.Service == "" {
			continue
		}
		if seen[r.Service] {
			continue
		}
		if filter != "" && r.Service != filter {
			continue
		}
		seen[r.Service] = true
		out = append(out, r)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Service < out[j].Service })
	return out
}

// aggregateDownstreamHealthDeps bundles the inputs the aggregator
// needs. Built by downstreamHealthHandler; consumed by
// aggregateDownstreamHealth.
type aggregateDownstreamHealthDeps struct {
	Routes        []Route
	Circuits      *CircuitRegistry
	Client        *http.Client
	Timeout       time.Duration
	Parallelism   int
	CorrelationID string
	ConfigVersion int64
	Tracer        trace.Tracer
	nowFn         func() time.Time
}

// aggregateDownstreamHealth fans out probes in parallel and
// assembles the response. Caller already loaded the snapshot and
// filtered the routes.
func aggregateDownstreamHealth(ctx context.Context, d aggregateDownstreamHealthDeps) DownstreamHealthResponse {
	now := d.nowFn().UTC()
	resp := DownstreamHealthResponse{
		Status:         "UP",
		CheckedAt:      now.Format(time.RFC3339Nano),
		CorrelationID:  d.CorrelationID,
		ConfigVersion:  d.ConfigVersion,
		ProbeTimeoutMs: d.Timeout.Milliseconds(),
		Totals:         DownstreamHealthTotals{Total: len(d.Routes)},
		Services:       make([]ServiceHealth, len(d.Routes)),
	}

	if len(d.Routes) == 0 {
		resp.Status = "SKIPPED_ONLY"
		return resp
	}

	parallelism := d.Parallelism
	if parallelism > len(d.Routes) {
		parallelism = len(d.Routes)
	}

	// Hard overall deadline: 8s ceiling regardless of probe count,
	// so a curl never hangs longer than 8s on the aggregator.
	aggCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()

	results := make([]ServiceHealth, len(d.Routes))
	var wg sync.WaitGroup
	jobs := make(chan int, len(d.Routes))
	for w := 0; w < parallelism; w++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			for idx := range jobs {
				route := d.Routes[idx]
				results[idx] = probeService(aggCtx, probeServiceDeps{
					Route:     route,
					Circuits:  d.Circuits,
					Client:    d.Client,
					Timeout:   d.Timeout,
					NowFn:     d.nowFn,
					Tracer:    d.Tracer,
					WorkerID:  worker,
					RequestID: d.CorrelationID,
				})
			}
		}(w)
	}
	for i := range d.Routes {
		jobs <- i
	}
	close(jobs)
	wg.Wait()

	resp.Services = results
	for _, r := range results {
		switch r.Status {
		case "UP":
			resp.Totals.OK++
		case "DEGRADED":
			resp.Totals.Degraded++
		case "DOWN":
			resp.Totals.Down++
		case "SKIPPED":
			resp.Totals.Skipped++
		}
	}

	resp.Status = classifyOverall(resp.Totals)
	return resp
}

// classifyOverall maps a totals row to the top-level status string.
func classifyOverall(t DownstreamHealthTotals) string {
	probed := t.Total - t.Skipped
	if probed == 0 {
		return "SKIPPED_ONLY"
	}
	if t.Down == 0 && t.Degraded == 0 {
		return "UP"
	}
	if t.OK > 0 {
		return "DEGRADED"
	}
	return "DOWN"
}

// probeServiceDeps is the per-route inputs for one probe.
type probeServiceDeps struct {
	Route     Route
	Circuits  *CircuitRegistry
	Client    *http.Client
	Timeout   time.Duration
	NowFn     func() time.Time
	Tracer    trace.Tracer
	WorkerID  int
	RequestID string
}

// probeService performs one /health probe. The breaker state is
// read for the response but the probe BYPASSES the breaker (uses a
// fresh http.Client with a short per-request timeout).
func probeService(ctx context.Context, d probeServiceDeps) ServiceHealth {
	now := d.NowFn().UTC()
	out := ServiceHealth{
		Service:      d.Route.Service,
		Upstream:     d.Route.Upstream,
		CheckedAt:    now.Format(time.RFC3339Nano),
		Endpoint:     "/health",
		CircuitState: circuitStateFor(d.Circuits, d.Route.Upstream),
	}

	if d.Route.Service == "" || d.Route.Upstream == "" {
		out.Status = "SKIPPED"
		out.Reason = "not_in_route_table"
		return out
	}

	probeURL, err := joinURL(d.Route.Upstream, "/health")
	if err != nil {
		out.Status = "DOWN"
		out.HTTPStatus = 0
		out.Downstream = &Downstream{
			Service: d.Route.Service,
			Code:    string(CodeDependencyUpstream),
			Status:  http.StatusBadGateway,
			Message: "invalid upstream URL: " + err.Error(),
		}
		return out
	}

	probeCtx, cancel := context.WithTimeout(ctx, d.Timeout)
	defer cancel()

	start := time.Now()
	req, reqErr := http.NewRequestWithContext(probeCtx, http.MethodGet, probeURL, nil)
	if reqErr != nil {
		out.Status = "DOWN"
		out.Downstream = &Downstream{
			Service:   d.Route.Service,
			Code:      string(CodeDependencyUpstream),
			Status:    http.StatusBadGateway,
			Message:   "could not build probe request: " + reqErr.Error(),
			LatencyMs: time.Since(start).Milliseconds(),
		}
		return out
	}
	// Propagate the request id so the downstream sees the same id
	// we report to the caller.
	if d.RequestID != "" {
		req.Header.Set("X-Request-Id", d.RequestID)
		req.Header.Set("X-Correlation-Id", d.RequestID)
	}
	req.Header.Set("User-Agent", "api-gateway-downstream-health/1")

	resp, err := d.Client.Do(req)
	latency := time.Since(start).Milliseconds()
	out.LatencyMs = latency
	if err != nil {
		out.Status = "DOWN"
		out.HTTPStatus = 0
		code := string(CodeDependencyUpstream)
		if isTimeoutError(err) || errorsIsDeadlineExceeded(probeCtx) {
			code = string(CodeDependencyTimeout)
		}
		out.Downstream = &Downstream{
			Service:   d.Route.Service,
			Code:      code,
			Status:    httpErrorStatusFor(code),
			LatencyMs: latency,
			Message:   err.Error(),
		}
		return out
	}
	defer resp.Body.Close()
	out.HTTPStatus = resp.StatusCode
	// Read at most 4 KiB of body for the status-field parse.
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))

	switch {
	case resp.StatusCode == http.StatusOK && hasUpStatus(body):
		out.Status = "UP"
	case resp.StatusCode == http.StatusOK && hasOutOfServiceStatus(body):
		out.Status = "DEGRADED"
		out.Downstream = &Downstream{
			Service:   d.Route.Service,
			Code:      string(CodeDependencyUnavailable),
			Status:    resp.StatusCode,
			LatencyMs: latency,
			Message:   "service reports OUT_OF_SERVICE",
		}
	case resp.StatusCode >= 500:
		out.Status = "DOWN"
		out.Downstream = &Downstream{
			Service:   d.Route.Service,
			Code:      string(CodeDependencyUpstream),
			Status:    resp.StatusCode,
			LatencyMs: latency,
			Message:   http.StatusText(resp.StatusCode),
		}
	default:
		out.Status = "DOWN"
		out.Downstream = &Downstream{
			Service:   d.Route.Service,
			Code:      string(CodeDependencyUpstream),
			Status:    resp.StatusCode,
			LatencyMs: latency,
			Message:   http.StatusText(resp.StatusCode),
		}
	}
	return out
}

// circuitStateFor maps the current gobreaker state to a string label
// for the response. Empty string when the registry has no breaker for
// the upstream yet.
func circuitStateFor(reg *CircuitRegistry, upstream string) string {
	if reg == nil {
		return "closed"
	}
	var st gobreaker.State
	found := false
	reg.ForEach(func(name string, br *CircuitBreaker) {
		if name != upstream {
			return
		}
		st = br.State()
		found = true
	})
	if !found {
		return "closed"
	}
	return stateLabel(st)
}

func stateLabel(s gobreaker.State) string {
	switch s {
	case gobreaker.StateClosed:
		return "closed"
	case gobreaker.StateHalfOpen:
		return "half-open"
	case gobreaker.StateOpen:
		return "open"
	default:
		return "closed"
	}
}

// joinURL concatenates a base + path without dropping the trailing
// slash on the base or producing "//path" on the join.
func joinURL(base, path string) (string, error) {
	if base == "" {
		return "", &url.Error{Op: "joinURL", URL: base, Err: errEmptyBase}
	}
	u, err := url.Parse(base)
	if err != nil {
		return "", err
	}
	if path == "" {
		return u.String(), nil
	}
	u.Path = strings.TrimRight(u.Path, "/") + "/" + strings.TrimLeft(path, "/")
	return u.String(), nil
}

type urlEmptyBaseError struct{}

func (urlEmptyBaseError) Error() string { return "empty base URL" }

var errEmptyBase = urlEmptyBaseError{}

// hasUpStatus returns true if the body parses as JSON and has
// `"status":"UP"` (case-insensitive). Tolerates non-JSON bodies.
func hasUpStatus(body []byte) bool {
	var p struct {
		Status string `json:"status"`
	}
	if err := json.Unmarshal(body, &p); err != nil {
		return false
	}
	return strings.EqualFold(p.Status, "UP")
}

// hasOutOfServiceStatus returns true if the body parses as JSON and
// has `"status":"OUT_OF_SERVICE"` or `"DOWN"`.
func hasOutOfServiceStatus(body []byte) bool {
	var p struct {
		Status string `json:"status"`
	}
	if err := json.Unmarshal(body, &p); err != nil {
		return false
	}
	return strings.EqualFold(p.Status, "OUT_OF_SERVICE") || strings.EqualFold(p.Status, "DOWN")
}

// httpErrorStatusFor picks a status code for the per-service failure
// envelope based on the platform error code. Used only when the
// probe never produced an HTTP response (so there is no upstream
// status code to forward).
func httpErrorStatusFor(code string) int {
	switch code {
	case string(CodeDependencyTimeout):
		return http.StatusGatewayTimeout
	case string(CodeDependencyUnavailable):
		return http.StatusServiceUnavailable
	default:
		return http.StatusBadGateway
	}
}

// isTimeoutError reports whether err looks like a timeout / deadline
// exceeded error. We avoid importing net.Error directly to keep the
// public surface tiny.
func isTimeoutError(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "timeout") || strings.Contains(s, "deadline exceeded")
}

// errorsIsDeadlineExceeded returns true if the probe context's
// deadline already fired.
func errorsIsDeadlineExceeded(ctx context.Context) bool {
	if ctx == nil {
		return false
	}
	if dl, ok := ctx.Deadline(); ok && time.Now().After(dl) {
		return true
	}
	return ctx.Err() == context.DeadlineExceeded
}
