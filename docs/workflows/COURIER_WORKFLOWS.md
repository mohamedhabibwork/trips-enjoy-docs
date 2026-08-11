# Courier Workflows

This document consolidates end-to-end flows that affect a courier's
lifecycle: onboarding, going online, accepting deliveries, completing
them, earning, withdrawing, and going offline. Reflects the
**21-service architecture** (20 absorbing survivors per
[ADR-0017](../architecture/adrs/0017-20-service-architecture.md) plus
the **Phase 7.7** `chat-service` addendum on 2026-08-12).

> For the **accounting view** of courier earnings (gross-to-net,
> commission, withholding, expense recognition, payable, payout,
> reconciliation) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Driver / Courier Income (Gross-to-Net)".
>
> **Chat handoff (Phase 7.7).** When a courier accepts a delivery
> (`delivery.courier.assigned.v1`), `chat-service` creates a
> `delivery_chat` thread with participants `[customer, courier]`.
> The thread closes on `delivery.completed.v1` /
> `delivery.cancelled.v1`. The chat is the only first-party
> communication channel; raw phone numbers are never exposed.
> Cross-cutting chat events are documented in
> [`chat-service/INTEGRATION.md`](../services/chat-service/INTEGRATION.md).

## Request lifecycle (parent events)

Per [ADR-0020](../architecture/adrs/0020-polymorphic-request-id.md), every delivery request emits polymorphic `request.*.v1` parent events. The domain events (`delivery.courier.assigned.v1`, `delivery.pickup.v1`, etc.) continue as children; `request.*.v1` is the parent layer for request-level subscription.

```mermaid
stateDiagram-v2
    [*] --> assigned: request.created.v1
    assigned --> in_progress: request.matched.v1
    in_progress --> delivered: request.completed.v1
    in_progress --> failed: request.failed.v1
    assigned --> failed: request.failed.v1
    delivered --> [*]
    failed --> [*]
```

Each `request.*.v1` event carries `request_id`, `service='courier_delivery'`, `workflow_process_id`, `status`, and `correlation_id`.

## Workflow: Courier Onboarding (KYC)

```mermaid
sequenceDiagram
    participant CR as Courier
    participant ID as identity-service
    participant COS as courier-service
    participant FS as file-service
    participant ADM as admin-service
    participant NOT as notification-service

    CR->>ID: register (phone)
    ID->>NOT: send OTP
```

## Workflow: Courier Online + Location

```mermaid
sequenceDiagram
    participant CR as Courier
    participant COS as courier-service
    participant GEO as geolocation-service

    CR->>COS: POST /v1/couriers/{id}/online (vehicle_type, zone_id)
    COS->>GEO: validate zone
    GEO-->>COS: ok
    COS->>COS: online=true (own producer)
    COS-->>CR: 200 OK
    loop online
        CR->>COS: stream location
        COS-->>CR: 200 OK (acks)
        COS->>COS: emit courier.location.updated.v1 (curated)
    end
```

## Workflow: Courier Accepts a Delivery

```mermaid
sequenceDiagram
    participant CR as Courier
    participant COS as courier-service (dispatch)
    participant DLV as delivery (in courier-service)
    participant FOR as food-order-service

    COS->>CR: delivery offer (push)
    CR-->>COS: accept
    COS->>DLV: delivery.courier.assigned.v1
    DLV->>DLV: state=assigned
    DLV-->>DLV: request.created.v1
    DLV->>DLV: request.matched.v1
    DLV->>FOR: food.order (courier assigned)
    CR->>DLV: navigate to restaurant
```

## Workflow: Delivery Lifecycle

```mermaid
stateDiagram-v2
    [*] --> assigned
    assigned --> en_route_pickup: courier moving
    en_route_pickup --> arrived_pickup: at restaurant
    arrived_pickup --> picked_up: order received
    picked_up --> en_route_dropoff: courier moving
    en_route_dropoff --> delivered: at customer, proof
    en_route_dropoff --> failed: cannot deliver
    picked_up --> failed: customer cancelled (rare)
    delivered --> [*]
    failed --> [*]
```

## Workflow: Delivery Failure (Customer Unreachable)

```mermaid
sequenceDiagram
    participant CR as Courier
    participant DLV as delivery (in courier-service)
    participant NOT as notification-service
    participant C as Customer
    participant ADM as admin-service (support module)
    participant PAY as payment-service (saga)
    participant COS as courier-service (dispatch)

    CR->>DLV: POST /v1/deliveries/{id}/failed (reason=customer_unreachable)
    DLV->>NOT: notify customer (call us)
    NOT-->>C: SMS / push
    Note over DLV: 5 min wait
    alt customer reaches support
        ADM->>DLV: re-dispatch
        DLV->>COS: re-dispatch
        COS->>DLV: delivery.courier.assigned.v1 (new)
        DLV-->>DLV: request.matched.v1
    else timeout
        DLV->>DLV: state=failed
        DLV-->>DLV: request.failed.v1
        DLV->>NOT: notify customer (returning to restaurant)
        DLV->>PAY: partial refund (per policy)
        DLV->>ADM: open ticket (via support.admin scope)
    end
```

## Workflow: Proof of Delivery

```mermaid
sequenceDiagram
    participant CR as Courier
    participant DLV as delivery (in courier-service)
    participant FS as file-service
    participant FOR as food-order-service

    CR->>DLV: POST /v1/deliveries/{id}/complete (proof_type, ...)
    alt photo proof
        CR->>FS: upload photo
        FS-->>CR: file_id
        CR->>DLV: complete (proof_type=photo, file_id=...)
    else signature
        CR->>DLV: complete (proof_type=signature, signature_b64=...)
    else pin code (default)
        C->>CR: provide pin
        CR->>DLV: complete (proof_type=pin, pin=...)
    end
    DLV->>DLV: state=delivered
    DLV-->>DLV: request.completed.v1
    DLV->>FOR: delivery.completed.v1
```

## Workflow: Courier Earnings

See [PAYMENT_WORKFLOWS.md](PAYMENT_WORKFLOWS.md). Couriers see
(in `payment-service`, which absorbed `courier-earnings-service`):

- Today's deliveries and pay.
- Weekly summary.
- Bonuses / quest progress.
- Withdrawal history.

## Workflow: Multi-Order Pickup (where supported)

```mermaid
sequenceDiagram
    participant CR as Courier
    participant COS as courier-service (dispatch)
    participant DLV1 as delivery (in courier-service, order 1)
    participant DLV2 as delivery (in courier-service, order 2)
    participant FOR as food-order-service

    COS->>CR: offer batch (orders 1 + 2 same restaurant)
    CR-->>COS: accept
    COS->>DLV1: delivery.courier.assigned.v1
    COS->>DLV2: delivery.courier.assigned.v1
    CR->>DLV1: pickup
    CR->>DLV1: en_route_dropoff
    CR->>DLV1: complete
    CR->>DLV2: pickup (already have it)
    CR->>DLV2: complete
```

The platform supports batched dispatch where orders come from the
same restaurant and the dropoffs are within a small radius.

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Document expires | Auto-suspend after grace period |
| Online but idle (no deliveries) | After T minutes, courier suggested to go offline |
| Cannot reach restaurant | Reassign after T minutes |
| Cannot reach customer | Re-dispatch or refund (per policy) |
| Item damaged | Photo + report; support ticket; possible partial refund |
| Bank details invalid | Courier prompted to update |
| App crash mid-delivery | Re-open app; trip state recovered from `courier-service` |

## Acceptance Criteria

- 99% of courier earnings are accrued within 5 minutes of delivery.
- 99% of failed deliveries are resolved (re-dispatched or refunded)
  within 30 minutes.
- 100% of proof-of-delivery images are stored encrypted.


## Conductor — Courier Onboarding

Courier onboarding mirrors driver onboarding per
[ADR-0018](../architecture/adrs/0018-workflow-engine-conductor.md) and
[`shared/CONDUCTOR_WORKFLOWS.md`](../shared/CONDUCTOR_WORKFLOWS.md) 3.4.

The workflow ID is `wf.onboarding.courier.v1` and has 8 tasks
mirroring `wf.onboarding.driver.v1` with the courier slice. The
owner is `courier-service`. SLA timers are identical (KYC 24h,
manual approval 24h, training 7 days, vehicle inspection 3 days).

The in-service courier state machine (per [ADR-0010](../architecture/adrs/0010-saga-pattern.md))
remains authoritative for the online/offline lifecycle and delivery
acceptance flow; only the long-running onboarding path uses
Conductor.
