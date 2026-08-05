# Ride Workflows

This document covers the end-to-end flows for the ride-hailing product.
Per-service state machines are in each service's `WORKFLOWS.md`.

> For the **accounting view** of ride transactions (customer transaction
> recognition; driver payable; tax; expense) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Customer Transaction Recognition (Ride / Food)".

## Actors and Services

| Actor | Services they touch directly |
|-------|------------------------------|
| Customer | `api-gateway`, `ride-request-service`, `trip-service`, `payment-service`, `review-rating-service`, `ride-safety-service` |
| Driver | `driver-availability-service`, `driver-location-service`, `dispatch-service`, `trip-service`, `driver-earnings-service` |
| System | `pricing-service`, `eta-routing-service`, `geolocation-service`, `notification-service`, `ride-payment-integration-service` |

## Workflow: Customer Requests a Ride (Happy Path)

```mermaid
sequenceDiagram
    participant C as Customer
    participant GW as api-gateway
    participant RR as ride-request-service
    participant PRC as pricing-service
    participant DSP as dispatch-service
    participant DA as driver-availability-service
    participant DL as driver-location-service
    participant DR as Driver
    participant TR as trip-service
    participant ETA as eta-routing-service
    participant NOT as notification-service

    C->>GW: POST /v1/rides (pickup, dropoff, ride_type)
    GW->>RR: create ride request
    RR->>PRC: quote(pickup, dropoff, ride_type)
    PRC-->>RR: PriceQuote
    RR->>RR: persist ride_request (state=requested)
    RR-->>C: 201 ride_request + quote
    RR->>DSP: ride.request.created.v1
    DSP->>DA: list available drivers in zone
    DA-->>DSP: drivers
    DSP->>DL: get current locations
    DL-->>DSP: locations
    DSP->>DR: offer ride (push)
    DR-->>DSP: accept
    DSP->>RR: dispatch.matched.v1
    RR->>TR: create trip (state=assigned)
    TR-->>RR: trip_id
    RR-->>C: ride matched
    RR->>NOT: notify customer (driver found)
    NOT-->>C: push: "Driver X is on the way"
    DR->>TR: POST /v1/trips/{id}/arrive
    TR->>NOT: notify customer
    DR->>TR: POST /v1/trips/{id}/start
    TR->>TR: state=in_progress
    TR-->>RR: trip.started.v1
    loop while in_progress
        DR->>TR: POST /v1/trips/{id}/location (every 5s)
    end
    DR->>TR: POST /v1/trips/{id}/complete (dropoff)
    TR->>TR: state=completed
    TR->>RR: trip.completed.v1
    RR-->>C: trip complete
```

State machine for `ride_request`:

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
    participant DA as driver-availability-service
    participant DL as driver-location-service
    participant DSP as dispatch-service

    DR->>DA: POST /v1/availability/online (vehicle_id, zone_id)
    DA->>DA: state=online
    DA->>DSP: driver.availability.online.v1
    DA-->>DR: 200 OK
    DR->>DL: stream location (every 1-5s)
    DL-->>DR: 200 OK (acks)
    DL->>DSP: driver.location.updated.v1 (curated)
```

## Workflow: Driver Accepts a Ride Offer

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DSP as dispatch-service
    participant RR as ride-request-service
    participant TR as trip-service

    DSP->>DR: ride offer (push)
    DR-->>DSP: accept (within 15s)
    DSP->>RR: dispatch.matched.v1 (driver_id, ride_request_id)
    RR->>TR: create trip
    TR-->>RR: trip_id
    RR->>RR: ride_request.state = matched -> trip_created
    RR-->>DR: trip details
```

If the driver does not respond in 15s, `dispatch.offer.expired.v1` is
emitted and the next driver is tried.

## Workflow: Customer Cancellation

```mermaid
sequenceDiagram
    participant C as Customer
    participant RR as ride-request-service
    participant PRC as pricing-service
    participant PAY as payment-service
    participant NOT as notification-service
    participant TR as trip-service

    C->>RR: POST /v1/rides/{id}/cancellation
    RR->>PRC: calculate cancellation fee
    PRC-->>RR: fee (may be 0)
    alt driver not yet assigned
        RR->>RR: state=cancelled (no fee)
        RR-->>C: 200 OK
    else driver assigned, not yet at pickup
        RR->>PAY: charge cancellation fee (Idempotency-Key=ride:R:cancel)
        PAY-->>RR: payment.captured.v1
        RR->>TR: trip cancelled
        RR->>NOT: notify driver
        RR-->>C: 200 OK
    else driver at pickup
        RR->>PRC: calculate fee (higher)
        RR->>PAY: charge
        RR-->>C: 200 OK
    end
```

## Workflow: Payment After Trip Completion

See [PAYMENT_WORKFLOWS.md](PAYMENT_WORKFLOWS.md) for the full saga.

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant OR as ride-payment-integration
    participant PAY as payment-service
    participant DE as driver-earnings-service
    participant LD as ledger-service
    participant RR as ride-request-service

    TR->>OR: trip.completed.v1
    OR->>PAY: capture(Idempotency-Key=trip:T:cap)
    PAY-->>OR: payment.captured.v1
    OR->>DE: accrue(Idempotency-Key=trip:T:earn)
    DE-->>OR: driver.earning.accrued.v1
    OR->>LD: post(saga=trip_completed)
    LD-->>OR: ledger.posted.v1
    OR-->>RR: ride.payment.completed.v1
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
    participant DE as driver-earnings-service
    participant WLT as wallet-service
    participant LD as ledger-service
    participant NOT as notification-service
    participant AUD as audit-service

    TR->>TR: trip.completed.v1 → evaluate rewards<br/>(driver_min_topup + customer_credit)
    TR->>OB: insert trip.reward.granted.v1
    OB-->>DE: trip.reward.granted.v1
    OB-->>WLT: trip.reward.granted.v1
    OB-->>AUD: trip.reward.granted.v1
    DE->>DE: apply top-up to earning accrual
    DE-->>LD: post(6302_guaranteed_minimum ↔ driver_payable)
    LD-->>DE: ledger.posted.v1
    WLT->>WLT: credit customer wallet
    WLT-->>LD: post(2100_customer_credit_liability ↔ cash)
    LD-->>WLT: ledger.posted.v1
    TR->>NOT: notify driver + customer (reward granted)
```

**Relationship with payment capture:** rewards are not gated on
`payment.captured.v1`. The `trip.completed.v1` event is the single
trigger; if capture fails, the failure-paths note below still grants
the driver the minimum guarantee via the wallet. Reversals on
cancellation or trip correction emit `trip.reward.reversed.v1`, which
is consumed by the same fan-out (`driver-earnings-service`,
`wallet-service`, `ledger-service`, `notification-service`,
`audit-service`) and produces **new postings**, never UPDATE/DELETE on
financial ledgers. See the accounting view in
[`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) §"Workflow:
Guaranteed Rewards — Driver Top-Up + Customer Credit".

## Workflow: Driver Cancellation

```mermaid
sequenceDiagram
    participant DR as Driver
    participant TR as trip-service
    participant RR as ride-request-service
    participant PRC as pricing-service
    participant DSP as dispatch-service
    participant NOT as notification-service

    DR->>TR: POST /v1/trips/{id}/cancel (reason)
    TR->>RR: trip.cancelled.v1
    RR->>PRC: calculate driver-cancellation penalty
    RR->>DSP: release driver (state=available)
    DSP->>RR: dispatch search for replacement
    RR->>NOT: notify customer (driver cancelled)
    alt replacement found within T
        DSP->>RR: dispatch.matched.v1 (new driver)
    else no replacement
        RR->>NOT: notify customer (no driver, please rebook)
        RR->>RR: state=cancelled (no fee)
    end
```

## Workflow: No Drivers Available

```mermaid
sequenceDiagram
    participant RR as ride-request-service
    participant DSP as dispatch-service
    participant NOT as notification-service
    participant C as Customer

    RR->>DSP: ride.request.created.v1
    DSP->>DSP: search (no drivers in T seconds)
    DSP->>RR: dispatch.no_driver.v1
    RR->>RR: state=expired
    RR->>NOT: notify customer
    NOT-->>C: push: "No drivers available. Try again."
```

## Workflow: Scheduled Ride

```mermaid
sequenceDiagram
    participant C as Customer
    participant SR as scheduled-ride-service
    participant RR as ride-request-service
    participant PRC as pricing-service
    participant DSP as dispatch-service

    C->>SR: POST /v1/scheduled-rides (pickup, dropoff, scheduled_for)
    SR->>PRC: quote
    PRC-->>SR: PriceQuote
    SR->>SR: persist job
    Note over SR: scheduler tick
    SR->>RR: scheduled_ride.due.v1 (T-15min)
    RR->>RR: create ride request
    RR->>DSP: ride.request.created.v1
    DSP-->>RR: matched
```

## Workflow: Safety / SOS

```mermaid
sequenceDiagram
    participant C as Customer
    participant RS as ride-safety-service
    participant TR as trip-service
    participant NOT as notification-service
    participant SUP as support-service
    participant SEC as Security

    C->>RS: POST /v1/safety/sos (trip_id, location)
    RS->>TR: get trip context
    TR-->>RS: trip details, driver info
    RS->>NOT: notify trusted contacts
    NOT-->>C: SMS + push
    RS->>SUP: open incident ticket (P1)
    SUP->>SEC: page on-call
    RS->>RS: persist incident (encrypted, audit)
    RS-->>C: 200 OK (we are with you)
```

## Workflow: Rating After Trip

```mermaid
sequenceDiagram
    participant C as Customer
    participant REV as review-rating-service
    participant DR as driver-service
    participant NOT as notification-service

    TR-->>REV: trip.completed.v1
    REV->>NOT: prompt for rating (24h after)
    NOT-->>C: push: "How was your ride?"
    C->>REV: POST /v1/reviews { trip_id, rating, comment }
    REV->>REV: persist review
    REV->>DR: review.submitted.v1
    DR->>DR: update aggregate rating
```

## Workflow: Driver Earnings Withdrawal

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DE as driver-earnings-service
    participant WLT as wallet-service
    participant PAY as payment-service
    participant LD as ledger-service

    DR->>DE: GET /v1/earnings/balance
    DE-->>DR: balance
    DR->>DE: POST /v1/earnings/withdrawals (amount)
    DE->>WLT: hold(amount)
    WLT-->>DE: wallet.held.v1
    DE->>PAY: payout to bank
    PAY-->>DE: payout.completed
    DE->>WLT: release(hold)
    WLT-->>DE: wallet.released.v1
    DE->>LD: post(withdrawal)
    LD-->>DE: ledger.posted.v1
    DE-->>DR: withdrawal.completed.v1
```

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Pricing service down | `ride-request-service` returns 503; gateway suggests "try again" |
| Dispatch service down | `ride-request-service` queues the request and returns 202 |
| Driver location stream lost | Dispatch uses last known location; alerts after 30s |
| Trip service down during a ride | Driver app retries; trip state recoverable from driver location trail and `dispatch.matched.v1` events |
| Payment capture fails | Saga in `ride-payment-integration-service` opens a support ticket; driver is still paid the minimum guarantee via wallet |
| Driver cancels mid-trip | `dispatch-service` searches for replacement; if none, customer rebooked with credit |
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
