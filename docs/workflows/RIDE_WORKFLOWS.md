# Ride Workflows

This document covers the end-to-end flows for the ride-hailing product
on the **20-service architecture** (consolidated 2026-08-05 per
[ADR-0016](../architecture/adrs/0016-service-domain-consolidation.md)
→ [ADR-0017](../architecture/adrs/0017-20-service-architecture.md)).
Per-service state machines are in each service's `WORKFLOWS.md`.

> For the **accounting view** of ride transactions (customer transaction
> recognition; driver payable; tax; expense) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Customer Transaction Recognition (Ride / Food)".

## Actors and Services

| Actor | Services they touch directly |
|-------|------------------------------|
| Customer | `api-gateway`, `trip-service` (ride-request), `payment-service` (wallet), `trip-service` (trip reviews), **`chat-service`** *(Phase 7.7 — rider ↔ driver chat during trip)* |
| Driver | `driver-service` (online, location, match), `trip-service` (trip), `payment-service` (earnings), **`chat-service`** *(Phase 7.7)* |
| System | `pricing-service`, `geolocation-service` (ETA / zones), `notification-service`, `payment-service` (saga), **`chat-service`** *(Phase 7.7 — creates `trip_chat` thread on `ride.request.matched.v1`, closes on `trip.completed.v1` / `trip.cancelled.v1`; offline push fallback)* |

## Request lifecycle (parent events)

Per [ADR-0020](../architecture/adrs/0020-polymorphic-request-id.md), every ride request emits polymorphic `request.*.v1` parent events that track the request-level state machine. The domain events (`ride.request.created.v1`, `trip.started.v1`, etc.) continue to exist as children; `request.*.v1` events are the parent layer that consumers needing request-level state can subscribe to instead of all domain events.

```mermaid
stateDiagram-v2
    [*] --> requested: request.created.v1
    requested --> matched: request.matched.v1
    matched --> in_progress: request.in_progress.v1
    in_progress --> completed: request.completed.v1
    requested --> cancelled: request.cancelled.v1
    requested --> failed: request.failed.v1
    matched --> cancelled: request.cancelled.v1
    in_progress --> cancelled: request.cancelled.v1
    completed --> [*]
    cancelled --> [*]
    failed --> [*]
```

Each `request.*.v1` event carries `request_id`, `service='trip'`, `workflow_process_id`, `status`, and `correlation_id`.

## Workflow: Customer Requests a Ride (Happy Path)

```mermaid
sequenceDiagram
    participant C as Customer
    participant GW as api-gateway
    participant TR as trip-service
    participant PRC as pricing-service
    participant DRV as driver-service
    participant DR as Driver
    participant ETA as geolocation-service
    participant NOT as notification-service

    C->>GW: POST /v1/rides (pickup, dropoff, ride_type)
    GW->>TR: create ride request
    TR->>PRC: quote(pickup, dropoff, ride_type)
    PRC-->>TR: PriceQuote
    TR->>TR: persist ride_request (state=requested)
    TR-->>TR: request.created.v1
    TR-->>C: 201 ride_request + quote
    TR->>DRV: ride.request.created.v1 (internal)
    DRV->>DRV: query embedded driver pool + match
    DRV->>DR: offer ride (push)
    DR-->>DRV: accept
    DRV->>TR: dispatch.matched.v1
    TR->>TR: create trip (state=assigned)
    TR-->>TR: request.matched.v1
    TR-->>C: ride matched
    TR->>NOT: notify customer (driver found)
    NOT-->>C: push: "Driver X is on the way"
    DR->>TR: POST /v1/trips/{id}/arrive
    TR->>NOT: notify customer
    DR->>TR: POST /v1/trips/{id}/start
    TR->>TR: state=in_progress
    TR-->>TR: request.in_progress.v1
    TR-->>TR: trip.started.v1
    loop while in_progress
        DR->>DRV: POST /v1/drivers/{id}/location (every 5s)
    end
    Note over C,DR: In-app chat *(Phase 7.7)*<br/>Rider + Driver chat via chat-service<br/>(trip_chat thread)
    DR->>TR: POST /v1/trips/{id}/complete (dropoff)
    TR->>TR: state=completed
    TR-->>TR: request.completed.v1
    TR-->>TR: trip.completed.v1
    TR-->>C: trip complete
```

State machine for `ride_request` (inside `trip.ride_requests`):

```mermaid
stateDiagram-v2
    [*] --> requested
    requested --> matched: dispatch.matched.v1
    requested --> cancelled: customer cancel
    requested --> expired: no driver found in T
    matched --> trip_created: trip-service creates trip
    matched --> cancelled: customer cancel (with fee)
    expired --> [*]
    cancelled --> [*]
    trip_created --> [*]
```

State machine for `trip`:

```mermaid
stateDiagram-v2
    [*] --> assigned
    assigned --> en_route_pickup: driver moving
    en_route_pickup --> arrived: driver at pickup
    arrived --> in_progress: trip started
    in_progress --> completed: dropoff confirmed
    in_progress --> cancelled: customer/driver cancel (mid-trip)
    assigned --> cancelled: customer cancel before pickup (with fee)
    completed --> [*]
    cancelled --> [*]
```

## Workflow: Driver Goes Online

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DRV as driver-service
    participant NOT as notification-service

    DR->>DRV: POST /v1/drivers/{id}/online (vehicle_id, zone_id)
    DRV->>DRV: state=online (own producer)
    DRV-->>DR: 200 OK
    loop online
        DR->>DRV: stream location (every 1-5s)
        DRV-->>DR: 200 OK (acks)
        DRV->>DRV: emit driver.location.updated.v1 (curated)
    end
```

## Workflow: Driver Accepts a Ride Offer

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DRV as driver-service
    participant TR as trip-service

    DRV->>DR: ride offer (push)
    DR-->>DRV: accept (within 15s)
    DRV->>TR: dispatch.matched.v1 (driver_id, request_id, service=trip)
    TR->>TR: create trip
    TR-->>TR: request.matched.v1
    TR-->>TR: ride_request.state = matched -> trip_created
    TR-->>DR: trip details
```

If the driver does not respond in 15s, `dispatch.offer.expired.v1` is
emitted and the next driver is tried.

## Workflow: Customer Cancellation

```mermaid
sequenceDiagram
    participant C as Customer
    participant TR as trip-service
    participant PRC as pricing-service
    participant PAY as payment-service
    participant NOT as notification-service

    C->>TR: POST /v1/rides/{id}/cancellation
    TR->>PRC: calculate cancellation fee
    PRC-->>TR: fee (may be 0)
    alt driver not yet assigned
        TR->>TR: state=cancelled (no fee)
        TR-->>TR: request.cancelled.v1
        TR-->>C: 200 OK
    else driver assigned, not yet at pickup
        TR->>PAY: charge cancellation fee (Idempotency-Key=ride:R:cancel)
        PAY-->>TR: payment.captured.v1
        TR->>TR: trip cancelled
        TR-->>TR: request.cancelled.v1
        TR->>NOT: notify driver
        TR-->>C: 200 OK
    else driver at pickup
        TR->>PRC: calculate fee (higher)
        TR->>PAY: charge
        TR-->>C: 200 OK
    end
```

## Workflow: Payment After Trip Completion

See [PAYMENT_WORKFLOWS.md](PAYMENT_WORKFLOWS.md) for the full saga.
The ride payment saga is now orchestrated inside `payment-service`
(absorbed from `ride-payment-integration-service`).

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant PAY as payment-service
    participant DRV as driver-service
    participant LD as ledger-service

    TR-->>PAY: trip.completed.v1
    PAY->>PAY: capture(Idempotency-Key=trip:T:cap)
    PAY-->>PAY: payment.captured.v1
    PAY->>PAY: accrue driver earning (Idempotency-Key=trip:T:earn)
    PAY-->>PAY: driver.earning.accrued.v1
    PAY->>LD: post(saga=trip_completed)
    LD-->>PAY: ledger.posted.v1
    PAY-->>TR: ride.payment.completed.v1
```

## Workflow: Guaranteed Rewards at Trip Completion

Trip completion triggers an independent **reward evaluation** in
`trip-service`: it determines both a possible driver top-up (when the
trip's accrued earnings fall below the city-configured minimum) and a
possible customer credit (loyalty / promo / issue-resolution). Rewards
are **independent of payment capture** — they fire on `trip.completed.v1`
regardless of whether capture succeeded (see the failure-paths note
"driver is still paid the minimum guarantee via wallet" below). The
grant is committed transactionally with the trip state change and
emitted via the `trip-service` outbox.

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant OB as outbox
    participant PAY as payment-service
    participant CUS as customer-service (wallet)
    participant LD as ledger-service
    participant NOT as notification-service
    participant AUD as audit-service

    TR->>TR: trip.completed.v1 → evaluate rewards<br/>(driver_min_topup + customer_credit)
    TR->>OB: insert trip.reward.granted.v1
    OB-->>PAY: trip.reward.granted.v1
    OB-->>CUS: trip.reward.granted.v1 (when user.kind = wallet_credit)
    OB-->>AUD: trip.reward.granted.v1
    PAY->>PAY: apply top-up to driver earning accrual
    PAY-->>LD: post(6302_guaranteed_minimum ↔ driver_payable)
    LD-->>PAY: ledger.posted.v1
    CUS->>CUS: credit customer wallet
    CUS-->>LD: post(2100_customer_credit_liability ↔ cash)
    LD-->>CUS: ledger.posted.v1
    TR->>NOT: notify driver + customer (reward granted)
```

**Relationship with payment capture:** rewards are not gated on
`payment.captured.v1`. The `trip.completed.v1` event is the single
trigger; if capture fails, the failure-paths note below still grants
the driver the minimum guarantee via the wallet. Reversals on
cancellation or trip correction emit `trip.reward.reversed.v1`, which
is consumed by the same fan-out (`payment-service`,
`customer-service` (wallet), `ledger-service`,
`notification-service`, `audit-service`) and produces **new postings**,
never UPDATE/DELETE on financial ledgers. See the accounting view in
[`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) "Workflow:
Guaranteed Rewards — Driver Top-Up + Customer Credit".

## Workflow: Driver Cancellation

```mermaid
sequenceDiagram
    participant DR as Driver
    participant TR as trip-service
    participant PRC as pricing-service
    participant DRV as driver-service
    participant NOT as notification-service

    DR->>TR: POST /v1/trips/{id}/cancel (reason)
    TR->>TR: trip.cancelled.v1
    TR->>TR: request.cancelled.v1
    TR->>PRC: calculate driver-cancellation penalty
    TR->>DRV: release driver (state=available)
    DRV->>TR: dispatch search for replacement
    TR->>NOT: notify customer (driver cancelled)
    alt replacement found within T
        DRV->>TR: dispatch.matched.v1 (new driver)
        TR->>TR: request.matched.v1
    else no replacement
        TR->>NOT: notify customer (no driver, please rebook)
        TR->>TR: state=cancelled (no fee)
    end
```

## Workflow: No Drivers Available

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant DRV as driver-service
    participant NOT as notification-service
    participant C as Customer

    TR-->>DRV: ride.request.created.v1
    DRV->>DRV: search (no drivers in T seconds)
    DRV-->>TR: dispatch.no_driver.v1
    TR->>TR: state=expired
    TR-->>TR: request.failed.v1
    TR->>NOT: notify customer
    NOT-->>C: push: "No drivers available. Try again."
```

## Workflow: Scheduled Ride

```mermaid
sequenceDiagram
    participant C as Customer
    participant TR as trip-service
    participant PRC as pricing-service
    participant DRV as driver-service

    C->>TR: POST /v1/rides/scheduled (pickup, dropoff, scheduled_for)
    TR->>PRC: quote
    PRC-->>TR: PriceQuote
    TR->>TR: persist scheduled job
    Note over TR: scheduler tick
    TR-->>TR: scheduled_ride.due.v1 (T-15min)
    TR->>TR: create ride request
    TR-->>TR: request.created.v1
    TR->>DRV: ride.request.created.v1 (internal)
    DRV-->>TR: matched
```

## Workflow: Safety / SOS

```mermaid
sequenceDiagram
    participant C as Customer
    participant TR as trip-service
    participant NOT as notification-service
    participant ADM as admin-service (support module)
    participant SEC as Security

    C->>TR: POST /v1/trips/{id}/sos (request_id, location)
    TR->>TR: get trip context
    TR-->>TR: trip details, driver info
    TR->>NOT: notify trusted contacts
    NOT-->>C: SMS + push
    TR->>ADM: open incident ticket (P1) (via support.admin scope)
    ADM->>SEC: page on-call
    TR->>TR: persist incident (encrypted, audit)
    TR-->>C: 200 OK (we are with you)
```

## Workflow: Rating After Trip

```mermaid
sequenceDiagram
    participant C as Customer
    participant TR as trip-service (trip reviews)
    participant DRV as driver-service
    participant NOT as notification-service

    TR-->>TR: trip.completed.v1
    TR->>NOT: prompt for rating (24h after)
    NOT-->>C: push: "How was your ride?"
    C->>TR: POST /v1/trips/{id}/review { trip_id, rating, comment }
    TR->>TR: persist review
    TR-->>DRV: review.submitted.v1
    DRV->>DRV: update aggregate rating
```

## Workflow: Driver Earnings Withdrawal

```mermaid
sequenceDiagram
    participant DR as Driver
    participant PAY as payment-service
    participant LD as ledger-service

    DR->>PAY: GET /v1/drivers/{id}/earnings
    PAY-->>DR: balance
    DR->>PAY: POST /v1/drivers/{id}/withdrawals (amount)
    PAY->>PAY: hold(amount)
    PAY-->>PAY: wallet.held.v1
    PAY->>PAY: payout to bank
    PAY-->>PAY: payout.completed
    PAY->>PAY: release(hold)
    PAY-->>PAY: wallet.released.v1
    PAY->>LD: post(withdrawal)
    LD-->>PAY: ledger.posted.v1
    PAY-->>DR: driver.withdrawal.completed.v1
```

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Pricing service down | `trip-service` returns 503; gateway suggests "try again" |
| Driver-service match subsystem down | `trip-service` queues the request and returns 202 |
| Driver location stream lost | `driver-service` uses last known location; alerts after 30s |
| Trip service down during a ride | Driver app retries; trip state recoverable from driver location trail and `dispatch.matched.v1` events |
| Payment capture fails | Saga in `payment-service` opens a support ticket (via `admin-service` support module); driver is still paid the minimum guarantee via wallet |
| Driver cancels mid-trip | `driver-service` searches for replacement; if none, customer rebooked with credit |
| No driver available | Customer prompted to rebook or join waitlist |

## Acceptance Criteria (end-to-end)

- A customer can complete a request-to-ride flow in < 60 seconds
  including payment.
- 99.5% of customers who request a ride are matched within 90
  seconds (target SLO; varies by city/time).
- 99.9% of completed trips have a successful payment capture.
- 99% of completed trips have a successful driver earning accrual
  within 5 minutes.
- 100% of safety incidents open a P1 support ticket within 60
  seconds.
- Each eligible trip produces a `trip.reward.granted.v1` within 1 second
  of trip completion (driver top-up + customer credit fan-out).


## Conductor — Phase 7 Rewards

The Guaranteed Rewards fan-out (per [ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md)
and [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 3.1)
runs on Netflix Conductor as two workflow definitions:

| Workflow ID | Trigger event | Tasks |
|---|---|---|
| `wf.phase7.reward_grant.v1` | `trip.reward.granted.v1` | 6 (driver earnings, wallet, ledger, notification, audit, reporting) |
| `wf.phase7.reward_reversal.v1` | `trip.reward.reversed.v1` | 6 (mirror with reversal semantics) |

The owner is `trip-service` (which emits the trigger events via its
outbox). The compensation steps run in reverse order on failure:
`compensate_payment_service_driver_earnings_grant` →
`compensate_payment_service_wallet_grant` → etc. The full
specification is in [`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 3.1.

The in-service trip state machine (per [ADR-0010](../architecture/adrs/0010-saga-pattern.md))
remains authoritative for the trip lifecycle itself; only the
**reward fan-out** (post-trip) is delegated to Conductor. This is a
targeted adoption — the trip lifecycle saga is not displaced.

## Related docs

- [`../SERVICE_INTEGRATION_MATRIX.md`](../SERVICE_INTEGRATION_MATRIX.md) — service × event × dependency matrix
- [`../architecture/EVENT_ARCHITECTURE.md`](../architecture/EVENT_ARCHITECTURE.md) — event catalog and delivery semantics
- [`../architecture/SERVICE_ISOLATION.md`](../architecture/SERVICE_ISOLATION.md) — downstream failure handling per class
