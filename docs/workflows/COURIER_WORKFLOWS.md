# Courier Workflows

This document consolidates end-to-end flows that affect a courier's
lifecycle: onboarding, going online, accepting deliveries, completing
them, earning, withdrawing, and going offline.

> For the **accounting view** of courier earnings (gross-to-net,
> commission, withholding, expense recognition, payable, payout,
> reconciliation) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Driver / Courier Income (Gross-to-Net)".

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
    NOT-->>CR: SMS
    CR->>ID: verify OTP
    ID-->>CR: kc_sub
    CR->>COS: POST /v1/couriers (kc_sub, profile, vehicle_type)
    COS->>FS: upload (id, vehicle_doc, selfie, bag_photo)
    FS-->>COS: file.uploaded.v1
    COS-->>CR: 201 pending review
    ADM->>COS: review
    alt approved
        COS->>COS: state=approved
        COS-->>NOT: notify courier
    else rejected
        COS-->>CR: reason
        CR->>COS: re-submit
    end
```

State machine for `courier`:

```mermaid
stateDiagram-v2
    [*] --> pending_review
    pending_review --> approved
    pending_review --> rejected
    pending_review --> expired
    rejected --> pending_review
    approved --> suspended
    approved --> inactive
    suspended --> approved
    inactive --> approved
    approved --> [*]
```

## Workflow: Courier Goes Online

```mermaid
sequenceDiagram
    participant CR as Courier
    participant COS as courier-service
    participant CTR as courier-tracking-service
    participant CDP as courier-dispatch-service
    participant ZN as zone-service

    CR->>COS: POST /v1/couriers/{id}/online (vehicle_type, zone_id)
    COS->>ZN: validate zone
    ZN-->>COS: ok
    COS->>COS: online=true
    COS->>CDP: courier.availability.online.v1
    COS-->>CR: 200 OK
    CR->>CTR: stream location
    CTR-->>CR: 200 OK
    CTR->>CDP: courier.location.updated.v1 (curated)
```

## Workflow: Courier Accepts a Delivery

```mermaid
sequenceDiagram
    participant CR as Courier
    participant CDP as courier-dispatch-service
    participant DLV as delivery-service
    participant FOR as food-order-service

    CDP->>CR: delivery offer (push)
    CR-->>CDP: accept
    CDP->>DLV: delivery.courier.assigned.v1
    DLV->>DLV: state=assigned
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
    participant DLV as delivery-service
    participant NOT as notification-service
    participant C as Customer
    participant SUP as support-service
    participant CDP as courier-dispatch-service
    participant FPI as food-payment-integration-service

    CR->>DLV: POST /v1/deliveries/{id}/failed (reason=customer_unreachable)
    DLV->>NOT: notify customer (call us)
    NOT-->>C: SMS / push
    Note over DLV: 5 min wait
    alt customer reaches support
        SUP->>DLV: re-dispatch
        DLV->>CDP: re-dispatch
        CDP->>DLV: delivery.courier.assigned.v1 (new)
    else timeout
        DLV->>DLV: state=failed
        DLV->>NOT: notify customer (returning to restaurant)
        DLV->>FPI: partial refund (per policy)
        DLV->>SUP: open ticket
    end
```

## Workflow: Proof of Delivery

```mermaid
sequenceDiagram
    participant CR as Courier
    participant DLV as delivery-service
    participant FS as file-service

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
    DLV->>FOR: delivery.completed.v1
```

## Workflow: Courier Earnings

See [PAYMENT_WORKFLOWS.md](PAYMENT_WORKFLOWS.md). Couriers see:

- Today's deliveries and pay.
- Weekly summary.
- Bonuses / quest progress.
- Withdrawal history.

## Workflow: Multi-Order Pickup (where supported)

```mermaid
sequenceDiagram
    participant CR as Courier
    participant CDP as courier-dispatch-service
    participant DLV1 as delivery (order 1)
    participant DLV2 as delivery (order 2)
    participant FOR as food-order-service

    CDP->>CR: offer batch (orders 1 + 2 same restaurant)
    CR-->>CDP: accept
    CDP->>DLV1: delivery.courier.assigned.v1
    CDP->>DLV2: delivery.courier.assigned.v1
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
| App crash mid-delivery | Re-open app; trip state recovered from `delivery-service` |

## Acceptance Criteria

- 99% of courier earnings are accrued within 5 minutes of delivery.
- 99% of failed deliveries are resolved (re-dispatched or refunded)
  within 30 minutes.
- 100% of proof-of-delivery images are stored encrypted.
