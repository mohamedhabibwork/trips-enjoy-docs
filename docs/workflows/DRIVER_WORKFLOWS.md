# Driver Workflows

This document consolidates end-to-end flows that affect a driver's
lifecycle: onboarding, going online, accepting rides, completing
trips, earning, withdrawing, and going offline. Reflects the
**20-service architecture** consolidated 2026-08-05 per
[ADR-0017](../architecture/adrs/0017-20-service-architecture.md).

> For the **accounting view** of driver earnings (gross-to-net,
> commission, withholding, expense recognition, payable, payout,
> reconciliation) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Driver / Courier Income (Gross-to-Net)".

## Workflow: Driver Onboarding (KYC)

```mermaid
sequenceDiagram
    participant DR as Driver
    participant ID as identity-service
    participant DRV as driver-service
    participant FS as file-service
    participant ADM as admin-service
    participant NOT as notification-service

    DR->>ID: register (phone)
    ID->>NOT: send OTP
    NOT-->>DR: SMS
    DR->>ID: verify OTP
    ID-->>DR: kc_sub
    DR->>DRV: POST /v1/drivers (kc_sub, profile)
    DRV->>DRV: state=pending_review
    DRV->>FS: upload (license, vehicle_reg, insurance, selfie)
    FS-->>DRV: file.uploaded.v1
    DRV-->>DR: 201 pending review
    ADM->>DRV: review (admin staff)
    alt approved
        ADM->>DRV: approve
        DRV->>DRV: state=approved
        DRV-->>NOT: notify driver
        NOT-->>DR: "Welcome, you're approved"
    else rejected
        ADM->>DRV: reject (reason)
        DRV-->>DR: 200 OK with reason
        DR->>DRV: re-submit documents
    end
```

State machine for `driver`:

```mermaid
stateDiagram-v2
    [*] --> pending_review: documents submitted
    pending_review --> approved: admin approves
    pending_review --> rejected: admin rejects
    pending_review --> expired: 30 days no decision
    rejected --> pending_review: re-submit
    approved --> suspended: fraud / quality
    approved --> inactive: long offline
    suspended --> approved: re-instated
    inactive --> approved: re-onboard
    approved --> [*]
```

## Workflow: Driver Goes Online

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DRV as driver-service
    participant GEO as geolocation-service

    DR->>DRV: POST /v1/drivers/{id}/online (vehicle_id, ride_types, zone_id)
    DRV->>GEO: validate zone
    GEO-->>DRV: ok
    DRV->>DRV: state=online (own producer)
    DRV-->>DR: 200 OK
    loop online
        DR->>DRV: stream location (1-5s)
        DRV-->>DR: 200 OK (acks)
        DRV->>DRV: emit driver.location.updated.v1 (curated)
    end
```

## Workflow: Driver Goes Offline

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DRV as driver-service
    participant TR as trip-service

    DR->>DRV: POST /v1/drivers/{id}/offline
    alt driver has active trip
        DRV-->>DR: 409 (cannot go offline)
    else
        DRV->>DRV: state=offline (own producer)
        DRV-->>DR: 200 OK
    end
```

If the driver tries to go offline mid-trip, the request is rejected.
The driver may go offline after `trip.completed.v1`.

## Workflow: Driver Accepts a Ride

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DRV as driver-service
    participant TR as trip-service

    DRV->>DR: ride offer (push)
    DR-->>DRV: accept (within 15s)
    DRV->>TR: dispatch.matched.v1
    TR->>TR: create trip
    TR-->>DRV: trip_id
    TR-->>DR: trip details
    DRV->>DRV: state=busy
    DRV->>DRV: emit driver.availability.busy.v1
```

## Workflow: Trip Lifecycle (driver view)

```mermaid
stateDiagram-v2
    [*] --> assigned
    assigned --> en_route_pickup: driver moving
    en_route_pickup --> arrived: at pickup
    arrived --> in_progress: trip started
    in_progress --> completed: dropoff
    in_progress --> cancelled: mid-trip cancel
    assigned --> cancelled: cancel before pickup
    completed --> [*]
    cancelled --> [*]
```

Driver actions map 1:1 to `trip-service` API calls (see
`trip-service/WORKFLOWS.md` for details).

## Workflow: Driver Earnings

See [PAYMENT_WORKFLOWS.md](PAYMENT_WORKFLOWS.md) for the financial
side. Driver-facing (in `payment-service`, which absorbed
`driver-earnings-service`):

```mermaid
sequenceDiagram
    participant DR as Driver
    participant PAY as payment-service

    DR->>PAY: GET /v1/drivers/{id}/earnings/today
    PAY-->>DR: today earnings
    DR->>PAY: GET /v1/drivers/{id}/earnings/week
    PAY-->>DR: weekly summary
    DR->>PAY: POST /v1/drivers/{id}/withdrawals (amount)
    PAY-->>DR: 202 (processing)
    Note over PAY: saga runs asynchronously
    PAY-->>DR: driver.withdrawal.completed.v1 (push)
```

## Workflow: Driver Withdrawal Failure

```mermaid
sequenceDiagram
    participant PAY as payment-service
    participant ADM as admin-service (support module)
    participant NOT as notification-service
    participant DR as Driver

    PAY->>PAY: hold(amount)
    PAY-->>PAY: wallet.held.v1
    PAY->>PAY: payout
    PAY-->>PAY: payout.failed (bank rejected)
    PAY->>PAY: release hold
    PAY-->>PAY: wallet.released.v1
    PAY->>ADM: open ticket (via support.admin scope)
    PAY->>NOT: notify driver
    NOT-->>DR: "Withdrawal failed, please update bank details"
    DR->>PAY: PATCH /v1/drivers/{id}/bank (new details)
    PAY->>PAY: retry payout
    PAY-->>PAY: ok
    PAY-->>DR: driver.withdrawal.completed.v1
```

## Workflow: Document Expiry

```mermaid
sequenceDiagram
    participant DRV as driver-service
    participant NOT as notification-service
    participant DR as Driver

    Note over DRV: nightly job
    DRV->>DRV: find expiring docs (30, 7, 1 day)
    DRV->>NOT: notify driver
    NOT-->>DR: "Your license expires in 7 days"
    alt driver uploads new doc
        DR->>DRV: upload
        DRV-->>DR: ok
    else expires
        DRV->>DRV: state=suspended (auto)
        DRV->>DRV: emit driver.suspended.v1
        DRV->>DRV: state=offline (own consumer)
    end
```

## Workflow: Driver Incentive Accrual

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant DRV as driver-service (incentives)
    participant PAY as payment-service (driver earnings)

    TR-->>DRV: trip.completed.v1
    DRV->>DRV: check quest / bonus rules
    alt eligible
        DRV->>PAY: accrue_bonus (Idempotency-Key=trip:T:bonus)
        PAY-->>DRV: ok
        DRV->>DRV: emit driver.incentive.earned.v1 (push)
    end
```

## Workflow: Driver Safety Incident

See [RIDE_WORKFLOWS.md](RIDE_WORKFLOWS.md) — "Safety / SOS". The
driver may also report a customer via the safety capability inside
`trip-service` (absorbed from `ride-safety-service`); this opens a
support ticket and may suspend the customer.

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Document expires | Auto-suspend after grace period |
| Online but not moving | Triggered by the embedded location stream in `driver-service`; after T minutes, marked suspicious |
| Repeated cancellations | Driver quality review; possible suspension |
| Negative balance (rare) | Reconciliation job opens a ticket |
| Withdrawal bank details invalid | Driver prompted to update; no funds moved |
| Mid-trip crash | `trip-service` detects heartbeat loss; if no recovery in T, opens a P1 safety ticket |

## Acceptance Criteria

- Driver can complete onboarding in < 24 hours of all documents
  being uploaded.
- 99% of document expiry warnings are sent 7+ days before expiry.
- 99% of trip completions result in earning accrual within 5 minutes.
- 100% of withdrawal failures are notified to the driver within
  1 minute.