# Make-a-Deal: InDriver-style Negotiation Kernel — Docs-Only Implementation Plan

## Goal

Add a **Make a Deal** (negotiation) capability to the trips-enjoy-platform docs repo that lets every request-driven service (ride, food, future verticals) accept either a **system-computed direct price** (plain accept/reject) or a **rider/driver counter-offer** bounded by geo-fenced min/max fare bands. Supports four mechanics: rider→driver counter, driver→rider counter, multi-driver bidding, geo-fenced fare bands. Embedded per service (no central deal-service binary), but the canonical contract lives in a shared hub doc.

**This is a docs-only deliverable.** No code. Every artefact is a markdown edit.

---

## Architecture decisions (locked)

| Decision | Choice | Rationale |
|---|---|---|
| Service topology | **Embedded per service** (per user) | Matches user's "embedded re-implementation" answer; aligns with platform's 'one service owns its data' rule. |
| Canonical contract home | **`docs/shared/DEAL_FEATURE.md`** | Mirrors `docs/shared/OSS_DEPENDENCIES.md` pattern; every service references it. |
| Per-service slice | **`## 12. Make a Deal` in TECH.md + INTEGRATION.md edits** | First new cross-service section after §11 OSS; sets precedent. |
| Fare-band storage | **New `pricing-service` rule kind `max_fare_override`** + config keys `deal.band.{scope}.{ride_type}` | Reuses existing `pricing.geo_overrides` infrastructure + `configuration-service` scope resolution. |
| Pricing integration | **Reuse `POST /v1/quotes` + new `GET /v1/quotes/{id}/fairness-band`** | Zero-friction: pricing already exposes `config_snapshot`, `min_fare`, `pricing_geo_overrides_matched`. |
| Driver-side mechanics | **New event family `deal.*.v1` published by ride-request-service / food-order-service; consumed by dispatch-service / courier-dispatch-service** | Mirrors existing `dispatch.offer.expired.v1` 15s-TTL precedent. |
| State machine | **Deal aggregate** with `open → negotiating → matched → expired` per `dispatch-service` state-machine style | Each participant service persists its own deal rows; saga correlation via `correlation_id`. |
| Push vs pull for drivers | **Hybrid** — push notification + `GET /v1/dispatch/drivers/{id}/open-deals` | Matches existing dispatch push pattern; adds pull for bidder discovery. |
| Counter-offer symmetric | **Yes** — both sides can counter, mediated by deal-attempt rounds | InDriver parity. |
| Event naming | `<domain>.deal.<verb>.v1` — e.g. `ride.deal.opened.v1`, `delivery.deal.bid.submitted.v1`, `ride.deal.countered.v1`, `ride.deal.accepted.v1`, `ride.deal.expired.v1` | Matches `ride.request.created.v1` style. |
| Audit chain | **Every state transition emits `deal.*.v1` → audit-service consumes** | Mirrors existing `audit.admin.<service>.v1` pattern (memory: `notification-immutable-template-audit-chain`). |
| Money | `amount_minor BIGINT` + `currency CHAR(3)` per `pricing-service` convention | Per memory `DATA--003`. |
| Idempotency | `Idempotency-Key: deal:<deal_id>:<action>` on every POST; inbox dedup on `event_id` | Per `architecture/FAILURE_HANDLING.md`. |
| Section numbering | **Append-only** — §12 is new, sits between §11 OSS and `## See also` | Per memory `trips-enjoy-docs-append-not-renumber`. |

---

## Deliverable 1 — `docs/shared/DEAL_FEATURE.md` (hub, NEW)

The canonical Make-a-Deal reference. Sections:

1. **Overview** — two offer flavors (deal / direct), four mechanics, embedded pattern.
2. **Bounded context** — `Deal` aggregate, `DealAttempt` round, `DealBid` counter-offer.
3. **State machine** — Mermaid `stateDiagram-v2` mirroring `dispatch-service` style:
   ```
   [*] → open: rider submits (with proposed_fare_minor)
   open → negotiating: ≥1 driver bids
   open → matched: a driver accepts the rider's price
   open → expired: deal_window_timeout
   negotiating → countered: rider/driver counters
   negotiating → matched: counter accepted
   negotiating → expired: all bids expired / max rounds hit
   matched → [*]   (then ride-request-service emits ride.request.created.v1)
   counter → negotiating: counterpart responds
   counter → matched: counterpart accepts
   counter → expired: no follow-up
   ```
4. **Event catalog** — Mermaid `flowchart` + table:
   - `ride.deal.opened.v1` (ride-request-service)
   - `ride.deal.bid.submitted.v1` (dispatch-service)
   - `ride.deal.countered.v1` (ride-request-service OR dispatch-service)
   - `ride.deal.accepted.v1` (ride-request-service)
   - `ride.deal.rejected.v1` (either side)
   - `ride.deal.expired.v1` (timer)
   - `food.deal.opened.v1`, `food.deal.bid.submitted.v1`, `food.deal.countered.v1`, `food.deal.accepted.v1`, `food.deal.expired.v1` (food-order-service + courier-dispatch-service)
   - All share the standard envelope (`event_id`, `aggregate_id=deal_id`, `correlation_id`).
5. **Fare-band resolution algorithm** — pseudocode: `band = pricing.fairness_band({city_id, ride_type, pickup, dropoff})` → resolves via `pricing.geo_overrides` (most-specific first) → fallback to `pricing.min_fare.{city_id}` for floor + `pricing.surge.max_multiplier * base_fare` for ceiling unless `max_fare_override` rule applies → returns `{min_fare_minor, max_fare_minor, currency, source}`.
6. **Money & currency** — `amount_minor BIGINT`, `currency CHAR(3)`, never floats.
7. **Idempotency, correlation, audit** — `Idempotency-Key: deal:<deal_id>:<action>`; `X-Correlation-Id` end-to-end; every transition emits `audit.deal.transition.v1` to audit-service.
8. **Configuration keys** (hosted in `configuration-service`, managed via `RECOMMENDATIONS.md` §6.2a adjacent):
   - `deal.window.ttl_seconds` (default 90)
   - `deal.bid.ttl_seconds` (default 15)
   - `deal.max_counter_rounds` (default 3)
   - `deal.band.{scope}.{ride_type}.{min_fare_minor,max_fare_minor,currency}`
   - `deal.broadcast.radius_m` (default 5000)
   - `deal.broadcast.max_concurrent_drivers` (default 10)
9. **Rollout & feature flag** — `feature-flag-service` key `deal.enabled.{city_id}.{ride_type}` (default OFF; admin-controlled).
10. **Per-service participation matrix** — table; cell value = either `participates` (with bullet list of what this service does) or `inherits` (single line: "This service does not participate in Make a Deal. See [`docs/shared/DEAL_FEATURE.md`](DEAL_FEATURE.md) §10 for the participation matrix.").
11. **See also** — links to `EVENT_ARCHITECTURE.md`, `FAILURE_HANDLING.md`, `pricing-service/INTEGRATION.md`, `configuration-service/README.md`.

---

## Deliverable 2 — `docs/services/ride-request-service/TECH.md` (EDIT)

Append **after** `## 11. Open-source bundle` and **before** `## See also` (memory `trips-enjoy-docs-append-not-renumber`):

```markdown
## 12. Make a Deal

This service participates in the platform's
[Make a Deal](../../shared/DEAL_FEATURE.md) negotiation kernel.

**Deal participation.** Ride-request-service is the **rider-side
boundary** for ride deals. It owns the `ride.deal.*` event family,
holds the `deal` aggregate rows in its schema, accepts rider direct
offers and counter-offers, and emits `ride.request.created.v1` once a
deal `matched`.

**Events.** Produces `ride.deal.opened.v1`, `ride.deal.countered.v1`,
`ride.deal.accepted.v1`, `ride.deal.rejected.v1`,
`ride.deal.expired.v1`. Consumes `dispatch.deal.bid.submitted.v1`
(see [`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md) §4
for the deal event catalog).

**Idempotency.** `Idempotency-Key: deal:<deal_id>:<action>` on every
state-changing POST; consumer-side inbox dedup on `event_id` per
[`architecture/FAILURE_HANDLING.md`](../../architecture/FAILURE_HANDLING.md).

**Single source of truth.** The deal model, state machine, fare-band
rules, and participation matrix live in
[`../../shared/DEAL_FEATURE.md`](../../shared/DEAL_FEATURE.md). The
config keys live in [`../RECOMMENDATIONS.md` §6.2a-adjacent](../RECOMMENDATIONS.md).
Do not duplicate the deal spec here.
```

---

## Deliverable 3 — `docs/services/ride-request-service/INTEGRATION.md` (EDIT)

Append a new section **after** §1 (Inbound APIs) and **before** §4 (Consumed Events):

- **New inbound endpoint `POST /v1/rides/{id}/deal`** — opens a deal on an existing ride request. Request carries `proposed_fare_minor` + `currency`. Response: `{deal_id, state, fairness_band:{min_fare_minor, max_fare_minor, source}, expires_at}`. Idempotency-Key required.
- **New inbound endpoint `POST /v1/deals/{id}/counter`** — rider submits a counter-offer against a specific driver bid. Request: `{bid_id, counter_fare_minor}`. Idempotency-Key required.
- **New inbound endpoint `POST /v1/deals/{id}/accept` and `POST /v1/deals/{id}/reject`** — terminal actions.
- **New inbound endpoint `GET /v1/deals/{id}`** — read.
- **§3 Produced Events** — append the 5 `ride.deal.*.v1` events with envelope + payload schema (mirror `dispatch.matched.v1` style).
- **§4 Consumed Events** — append `dispatch.deal.bid.submitted.v1` (consumer-side dedup note).

---

## Deliverable 4 — `docs/services/dispatch-service/TECH.md` + `INTEGRATION.md` (EDIT)

- **TECH.md §12** — same template as ride-request-service, but describing dispatch-service as the **driver-side boundary**: enumerates drivers, fans out bids, enforces bid TTL, emits `dispatch.deal.bid.submitted.v1`.
- **INTEGRATION.md** — add inbound endpoint `POST /v1/dispatch/deals/{deal_id}/bids` (driver submits a bid against a deal), `POST /v1/dispatch/deals/{deal_id}/accept` (driver accepts rider's counter), `GET /v1/dispatch/drivers/{driver_id}/open-deals` (pull discovery for bidder model). Add §3 events `dispatch.deal.bid.submitted.v1`, `dispatch.deal.bid.expired.v1`. Add §4 consumer `ride.deal.opened.v1`, `ride.deal.countered.v1`.

---

## Deliverable 5 — `docs/services/food-order-service/TECH.md` + `INTEGRATION.md` (EDIT)

Same template as ride-request-service, but uses `food.deal.*.v1` event family. The "rider" is the customer; the "driver" is the courier. courier-dispatch-service is the dispatcher analogue.

Tech.md §12 + INTEGRATION.md: endpoints `POST /v1/orders/{id}/deal`, `POST /v1/deals/{id}/counter|accept|reject`, `GET /v1/deals/{id}`. Events emitted: `food.deal.opened.v1`, `food.deal.countered.v1`, `food.deal.accepted.v1`, `food.deal.rejected.v1`, `food.deal.expired.v1`. Consumed: `delivery.deal.bid.submitted.v1`.

---

## Deliverable 6 — `docs/services/pricing-service/INTEGRATION.md` (EDIT)

- **New endpoint `GET /v1/quotes/{quote_id}/fairness-band`** — returns the fare band that bounds negotiation for a given quote. Auth: `pricing.read`. Body: `{min_fare_minor, max_fare_minor, currency, source:{kind:'min_fare_override'|'max_fare_override'|'base_band', rule_id, version}, config_snapshot}`. Errors: `404 QUOTE_NOT_FOUND`, `410 QUOTE_EXPIRED`.
- **New rule_kind in `pricing.geo_overrides`** — `max_fare_override` joins existing enum (`base_fare_override`, `per_km_override`, `per_min_override`, `surge_pressure`, `loyalty_discount`, `min_fare_override`, `od_corridor`). Resolution order: `od_corridor` > `max_fare_override` > `min_fare_override` > `base_fare_override` > `per_km_override` > `per_min_override`.
- **§3 Produced events** — add `pricing.fairness_band.computed.v1` (so admin-service can audit which rule fired).

---

## Deliverable 7 — `docs/services/configuration-service/README.md` (EDIT)

- **§13 Configuration** — append the `deal.*` config keys (band / TTL / counter-round / broadcast) and their schema `{type: 'object', properties: {min: {...}, max: {...}}, required: ['min','max']}` — mirroring `restaurant.max_active_orders` (§4.5 of INTEGRATION.md).
- Note the hierarchical scope: `deal.band.{tenant_id}.{city_id}.{ride_type}.{min_fare_minor, max_fare_minor, currency}`.

---

## Deliverable 8 — `docs/services/zone-service/TECH.md` (EDIT)

- **§12 Make a Deal** — single-line **inherits** block (per the strict-patch-mode? actually minimum scope per the user: just reference `docs/shared/DEAL_FEATURE.md` §10). Per `RECOMMENDATIONS.md:543-545` precedent for *inherited* admin endpoints.

---

## Deliverable 9 — `docs/services/notification-service/TECH.md` + `INTEGRATION.md` (EDIT)

- **TECH.md §12** — describes notification-service as the **delivery surface** for deal events (rider gets "driver countered", driver gets "rider countered", either side gets "deal expired").
- **INTEGRATION.md** — add 5 new templates: `deal.opened`, `deal.bid_received`, `deal.counter_received`, `deal.accepted`, `deal.expired`. Mirror the immutable-template audit chain (memory `notification-immutable-template-audit-chain`): each notification binds to `template_version_snapshot_id`.
- **§4 Consumed events** — add `ride.deal.*.v1` and `food.deal.*.v1` (all 5 event types).

---

## Deliverable 10 — `docs/services/RECOMMENDATIONS.md` (EDIT)

- **§6.2a-adjacent** — add a new sub-section `### 6.2b Deal kernel participation` describing the cross-service participation matrix in one place, mirroring §6.2a's `SUPER_ADMIN` preset table. Reference `docs/shared/DEAL_FEATURE.md` §10 as the canonical source.
- **§5 (Cross-cutting tooling list)** — note the new `feature_flag.deal.enabled.{city_id}.{ride_type}` key.

---

## Deliverable 11 — `docs/IMPLEMENTATION_PHASES.md` (EDIT)

- Add a new **Phase 7.5** (or extend Phase 7) row: "Make a Deal kernel — Pricing fairness-band + Configuration deal.* keys + Ride / Dispatch / Food integration + Notification templates". Estimated 2 weeks.

---

## Files touched (summary)

| File | Type | Purpose |
|---|---|---|
| `docs/shared/DEAL_FEATURE.md` | NEW (~600 lines) | Canonical hub |
| `docs/services/ride-request-service/TECH.md` | EDIT | §12 Make a Deal |
| `docs/services/ride-request-service/INTEGRATION.md` | EDIT | deal endpoints + events |
| `docs/services/dispatch-service/TECH.md` | EDIT | §12 Make a Deal |
| `docs/services/dispatch-service/INTEGRATION.md` | EDIT | bid endpoints + events |
| `docs/services/food-order-service/TECH.md` | EDIT | §12 Make a Deal |
| `docs/services/food-order-service/INTEGRATION.md` | EDIT | deal endpoints + events |
| `docs/services/pricing-service/INTEGRATION.md` | EDIT | fairness-band endpoint + max_fare_override rule_kind |
| `docs/services/configuration-service/README.md` | EDIT | deal.* config keys |
| `docs/services/zone-service/TECH.md` | EDIT | §12 inherits block |
| `docs/services/notification-service/TECH.md` | EDIT | §12 Make a Deal |
| `docs/services/notification-service/INTEGRATION.md` | EDIT | 5 templates + consumed events |
| `docs/services/RECOMMENDATIONS.md` | EDIT | §6.2b deal participation table |
| `docs/IMPLEMENTATION_PHASES.md` | EDIT | Phase 7.5 row |

**Total: 1 new + 13 edits.** No code. No new directories under `docs/services/`.

---

## Conventions enforced

- **Append, never renumber** (memory `trips-enjoy-docs-append-not-renumber`). §12 sits between §11 OSS and `## See also`.
- **Event naming**: `<domain>.deal.<verb>.v1`, mirror `ride.request.created.v1` style.
- **Event envelope**: standard shared kernel (`event_id`, `aggregate_id=deal_id`, `correlation_id`, `config_snapshot`).
- **Money**: `amount_minor BIGINT` + `currency CHAR(3)` (DATA--003).
- **Idempotency**: `Idempotency-Key: deal:<deal_id>:<action>` + inbox dedup on `event_id`.
- **Config keys**: dotted `<service>.<feature>.<field>`, with schema `{type, minimum, maximum}` per `configuration-service` §4.5.
- **Audit chain**: every deal transition emits `audit.deal.transition.v1` to audit-service (mirrors `audit.admin.<service>.v1`).
- **State machine**: Mermaid `stateDiagram-v2`, mirrors `dispatch-service` state-machine style.
- **Cross-service section template**: 5 bold-label paragraphs + See also, mirroring §11 OSS shape.

---

## Out of scope (explicit)

- No new service binary — embedded per user choice.
- No payment integration changes — `pricing-service` is the only money touch-point. Deal `accepted` flows through existing `trip-service` capture path.
- No new `city-to-city-service` — your ride-request-service and food-order-service are the only two consumer services; city-to-city is left as a future addition (the hub doc's participation matrix accommodates it).
- No DB DDL — schema sketches live in `ERD.md` only if needed; this plan keeps the deal aggregate in the participating service's existing schema.
- No code changes.

---

## Execution order (when plan is approved)

1. Create `docs/shared/DEAL_FEATURE.md` (the hub).
2. Edit `docs/services/pricing-service/INTEGRATION.md` (pricing is the dependency everyone else needs).
3. Edit `docs/services/configuration-service/README.md` (config keys).
4. Edit `docs/services/ride-request-service/TECH.md` + `INTEGRATION.md`.
5. Edit `docs/services/dispatch-service/TECH.md` + `INTEGRATION.md`.
6. Edit `docs/services/food-order-service/TECH.md` + `INTEGRATION.md`.
7. Edit `docs/services/notification-service/TECH.md` + `INTEGRATION.md`.
8. Edit `docs/services/zone-service/TECH.md` (inherits block).
9. Edit `docs/services/RECOMMENDATIONS.md` (§6.2b).
10. Edit `docs/IMPLEMENTATION_PHASES.md` (Phase 7.5 row).
11. Verify with `grep -rn "Deal\|Negotiat\|deal\." docs/services/*/TECH.md | wc -l` to confirm 9 services have §12.

**Estimated effort**: ~1,500 lines of new markdown + ~200 lines of edits across 13 files.