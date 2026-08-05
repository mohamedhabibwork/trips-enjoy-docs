# Driver Workflows

This document consolidates end-to-end flows that affect a driver's
lifecycle: onboarding, going online, accepting rides, completing
trips, earning, withdrawing, and going offline.

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
    participant DA as driver-availability-service
    participant DL as driver-location-service
    participant DSP as dispatch-service
    participant ZN as zone-service

    DR->>DA: POST /v1/availability/online (vehicle_id, ride_types, zone_id)
    DA->>ZN: validate zone
    ZN-->>DA: ok
    DA->>DA: state=online
    DA->>DSP: driver.availability.online.v1
    DA-->>DR: 200 OK
    DR->>DL: stream location (1-5s)
    DL-->>DR: 200 OK (acks)
    DL->>DSP: driver.location.updated.v1 (curated)
```

## Workflow: Driver Goes Offline

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DA as driver-availability-service
    participant DSP as dispatch-service
    participant TR as trip-service

    DR->>DA: POST /v1/availability/offline
    alt driver has active trip
        DA-->>DR: 409 (cannot go offline)
    else
        DA->>DA: state=offline
        DA->>DSP: driver.availability.offline.v1
        DA-->>DR: 200 OK
    end
```

If the driver tries to go offline mid-trip, the request is rejected.
The driver may go offline after `trip.completed.v1`.

## Workflow: Driver Accepts a Ride

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DSP as dispatch-service
    participant RR as ride-request-service
    participant TR as trip-service

    DSP->>DR: ride offer (push)
    DR-->>DSP: accept (within 15s)
    DSP->>RR: dispatch.matched.v1
    RR->>TR: create trip
    TR-->>RR: trip_id
    RR-->>DR: trip details
    DA->>DA: state=busy
    DA->>DSP: driver.availability.busy.v1
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
side. Driver-facing:

```mermaid
sequenceDiagram
    participant DR as Driver
    participant DE as driver-earnings-service

    DR->>DE: GET /v1/earnings/today
    DE-->>DR: today earnings
    DR->>DE: GET /v1/earnings/week
    DE-->>DR: weekly summary
    DR->>DE: POST /v1/earnings/withdrawals (amount)
    DE-->>DR: 202 (processing)
    Note over DE: saga runs asynchronously
    DE-->>DR: driver.withdrawal.completed.v1 (push)
```

## Workflow: Driver Withdrawal Failure

```mermaid
sequenceDiagram
    participant DE as driver-earnings-service
    participant WLT as wallet-service
    participant PAY as payment-service
    participant SUP as support-service
    participant NOT as notification-service
    participant DR as Driver

    DE->>WLT: hold(amount)
    WLT-->>DE: ok
    DE->>PAY: payout
    PAY-->>DE: payout.failed (bank rejected)
    DE->>WLT: release hold
    WLT-->>DE: ok
    DE->>SUP: open ticket
    DE->>NOT: notify driver
    NOT-->>DR: "Withdrawal failed, please update bank details"
    DR->>DE: PATCH /v1/earnings/bank (new details)
    DE->>PAY: retry payout
    PAY-->>DE: ok
    DE-->>DR: driver.withdrawal.completed.v1
```

## Workflow: Document Expiry

```mermaid
sequenceDiagram
    participant DRV as driver-service
    participant DA as driver-availability-service
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
        DRV->>DA: driver.suspended.v1
        DA->>DA: state=offline
    end
```

## Workflow: Driver Incentive Accrual

```mermaid
sequenceDiagram
    participant TR as trip-service
    participant DIN as driver-incentive-service
    participant DE as driver-earnings-service

    TR->>DIN: trip.completed.v1
    DIN->>DIN: check quest / bonus rules
    alt eligible
        DIN->>DE: accrue_bonus (Idempotency-Key=trip:T:bonus)
        DE-->>DIN: ok
        DIN-->>DR: driver.incentive.earned.v1 (push)
    end
```

## Workflow: Driver Safety Incident

See [RIDE_WORKFLOWS.md](RIDE_WORKFLOWS.md) — "Safety / SOS". The
driver may also report a customer via `ride-safety-service`; this
opens a support ticket and may suspend the customer.

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Document expires | Auto-suspend after grace period |
| Online but not moving | Triggered by `driver-location-service`; after T minutes, marked suspicious |
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
