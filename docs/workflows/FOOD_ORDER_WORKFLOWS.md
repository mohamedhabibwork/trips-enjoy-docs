# Food Order Workflows

This document covers the end-to-end flows for the food delivery
product. Per-service state machines are in each service's `WORKFLOWS.md`.

> For the **accounting view** of food order transactions (customer
> transaction recognition; merchant payable; courier earning;
> marketplace VAT; expense) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Customer Transaction Recognition (Ride / Food)" and "Workflow:
> Restaurant Settlement & Marketplace VAT".

## Actors and Services

| Actor | Services they touch directly |
|-------|------------------------------|
| Customer | `cart-service`, `checkout-service`, `food-order-service`, `payment-service`, `review-rating-service` |
| Restaurant operator | `restaurant-order-mgmt-service`, `menu-service`, `branch-service` |
| Courier | `courier-dispatch-service`, `delivery-service`, `courier-earnings-service` |
| System | `merchant-service`, `restaurant-service`, `inventory-service`, `promotion-service`, `pricing-service`, `tax-service`, `notification-service`, `food-payment-integration-service`, `restaurant-settlement-service` |

## Workflow: Customer Places a Food Order (Happy Path)

```mermaid
sequenceDiagram
    participant C as Customer
    participant CRT as cart-service
    participant MN as menu-service
    participant PRC as pricing-service
    participant PRM as promotion-service
    participant CHK as checkout-service
    participant PAY as payment-service
    participant FOR as food-order-service
    participant RES as restaurant-service
    participant ROM as restaurant-order-mgmt-service
    participant CDP as courier-dispatch-service
    participant DLV as delivery-service
    participant FPI as food-payment-integration-service
    participant NOT as notification-service
    participant CE as courier-earnings-service
    participant RSM as restaurant-settlement-service

    C->>CRT: POST /v1/carts (items)
    CRT->>MN: get menu
    MN-->>CRT: menu
    CRT->>PRC: quote
    PRC->>PRM: apply promotion
    PRM-->>PRC: discount
    PRC-->>CRT: subtotal
    C->>CRT: PATCH /v1/carts/{id} (address, tip)
    C->>CHK: POST /v1/checkouts (cart_id, payment_method)
    CHK->>PAY: authorize (Idempotency-Key=cart:C:auth)
    PAY-->>CHK: payment.authorized.v1
    CHK->>FOR: create order
    FOR->>FOR: state=placed
    FOR-->>CHK: order_id
    CHK-->>C: 201 order
    FOR->>ROM: food.order.placed.v1
    ROM->>RES: notify restaurant
    RES-->>ROM: accept (within T minutes)
    ROM->>FOR: food.order.accepted.v1
    FOR->>FOR: state=accepted -> preparing
    FOR->>ROM: food.order.preparing.v1
    Note over ROM: kitchen marks ready
    ROM->>FOR: food.order.ready.v1
    FOR->>FOR: state=ready
    FOR->>CDP: food.order.ready.v1
    CDP->>CDP: find courier
    CDP->>DLV: delivery.courier.assigned.v1
    DLV->>FOR: delivery.pickup.v1
    DLV->>DLV: state=in_transit
    DLV->>DLV: state=delivered (proof)
    DLV->>FOR: delivery.completed.v1
    FOR->>FPI: delivery.completed.v1
    FPI->>PAY: capture (Idempotency-Key=order:O:cap)
    PAY-->>FPI: payment.captured.v1
    FPI->>CE: courier.earning.accrued.v1
    FPI->>RSM: merchant.settlement.accrued.v1
    FPI->>FOR: food.payment.completed.v1
    FOR->>FOR: state=delivered
    FOR->>NOT: notify customer
    NOT-->>C: push: "Order delivered"
    FOR->>REV: review prompt
    REV-->>C: push: "How was your order?"
```

State machine for `food_order`:

```mermaid
stateDiagram-v2
    [*] --> placed
    placed --> accepted: restaurant accepts within T
    placed --> rejected: restaurant rejects
    placed --> cancelled: customer cancels before accept
    accepted --> preparing: kitchen starts
    preparing --> ready: kitchen marks ready
    ready --> courier_assigned: dispatch matches courier
    ready --> cancelled: customer cancels (with fee)
    courier_assigned --> picked_up: courier picks up
    picked_up --> delivered: courier delivers
    picked_up --> failed: courier cannot deliver
    delivered --> [*]
    cancelled --> [*]
    rejected --> [*]
    failed --> [*]
```

## Workflow: Cart Lifecycle

```mermaid
sequenceDiagram
    participant C as Customer
    participant CRT as cart-service
    participant MN as menu-service
    participant PRM as promotion-service

    C->>CRT: POST /v1/carts (items)
    CRT->>MN: validate items, prices
    MN-->>CRT: ok
    CRT->>CRT: persist (state=active)
    loop as user edits
        C->>CRT: PATCH /v1/carts/{id} (add/remove items)
        CRT->>MN: re-validate
        CRT->>CRT: re-quote
    end
    opt apply promotion
        C->>CRT: POST /v1/carts/{id}/promotions { code }
        CRT->>PRM: validate
        PRM-->>CRT: ok
        CRT->>CRT: apply
    end
    C->>CRT: POST /v1/carts/{id}/checkout
    CRT->>CHK: create checkout session
    Note over CRT: 30 min idle -> cart.abandoned.v1
```

If the menu item becomes unavailable while the cart is active,
`menu.item.unavailable.v1` is consumed; the item is removed from the
cart and the user is notified.

If the price changes, `menu.item.price.changed.v1` triggers a
re-quote; the cart is updated and the user is notified of the diff.

## Workflow: Restaurant Acceptance (with timer)

```mermaid
sequenceDiagram
    participant FOR as food-order-service
    participant ROM as restaurant-order-mgmt-service
    participant RES as restaurant-service
    participant NOT as notification-service
    participant C as Customer
    participant CDP as courier-dispatch-service
    participant FPI as food-payment-integration-service

    FOR->>ROM: food.order.placed.v1
    Note over ROM: 5-minute accept timer
    alt restaurant accepts
        RES-->>ROM: accept
        ROM->>FOR: food.order.accepted.v1
    else restaurant rejects
        RES-->>ROM: reject (reason)
        ROM->>FOR: food.order.rejected.v1
        FOR->>FPI: trigger refund saga
        FPI->>FPI: refund authorization
        FOR->>NOT: notify customer
        NOT-->>C: push: "Restaurant can't fulfill, refunded"
    else timer expires
        ROM->>FOR: food.order.rejected.v1 (reason=auto_reject)
        FOR->>FPI: trigger refund
        FOR->>NOT: notify customer
    end
```

## Workflow: Courier Dispatch and Delivery

```mermaid
sequenceDiagram
    participant FOR as food-order-service
    participant CDP as courier-dispatch-service
    participant CTR as courier-tracking-service
    participant CUR as Courier
    participant DLV as delivery-service
    participant NOT as notification-service

    FOR->>CDP: food.order.ready.v1
    CDP->>CDP: search available couriers
    CDP->>CTR: get current locations
    CTR-->>CDP: locations
    CDP->>CUR: offer (push)
    CUR-->>CDP: accept
    CDP->>DLV: delivery.courier.assigned.v1
    DLV->>DLV: state=assigned
    CUR->>DLV: POST /v1/deliveries/{id}/arrived (at restaurant)
    DLV->>NOT: notify restaurant (courier arrived)
    CUR->>DLV: POST /v1/deliveries/{id}/pickup
    DLV->>DLV: state=picked_up
    DLV->>NOT: notify customer (courier on the way)
    CUR->>DLV: POST /v1/deliveries/{id}/complete (proof)
    DLV->>DLV: state=delivered
    DLV->>NOT: notify customer (delivered)
```

## Workflow: Customer Cancellation (with policy)

```mermaid
sequenceDiagram
    participant C as Customer
    participant FOR as food-order-service
    participant PRC as pricing-service
    participant FPI as food-payment-integration-service
    participant NOT as notification-service

    C->>FOR: POST /v1/orders/{id}/cancellation
    FOR->>PRC: calculate cancellation fee
    PRC-->>FOR: fee
    alt before restaurant accept
        FOR->>FPI: full refund
        FPI-->>FOR: refund.completed.v1
        FOR->>FOR: state=cancelled
        FOR-->>C: 200 OK
    else after accept, before ready
        FOR->>FPI: partial refund (less restaurant cancel fee)
        FPI-->>FOR: partial refund
        FOR->>NOT: notify restaurant
        FOR-->>C: 200 OK
    else after ready
        FOR-->>C: 409 (cannot cancel, courier already en route)
    end
```

## Workflow: Restaurant Cancellation

```mermaid
sequenceDiagram
    participant RES as Restaurant
    participant ROM as restaurant-order-mgmt-service
    participant FOR as food-order-service
    participant FPI as food-payment-integration-service
    participant NOT as notification-service
    participant C as Customer
    participant SUP as support-service

    RES->>ROM: POST /v1/orders/{id}/cancel (reason)
    ROM->>FOR: food.order.rejected.v1 (reason=restaurant_cancel)
    FOR->>FPI: full refund
    FPI-->>FOR: refund.completed.v1
    FOR->>NOT: notify customer
    NOT-->>C: push: "Restaurant cancelled, full refund"
    FOR->>SUP: open ticket (for restaurant quality review)
```

## Workflow: Courier Cancellation / Reassignment

```mermaid
sequenceDiagram
    participant CUR as Courier
    participant DLV as delivery-service
    participant CDP as courier-dispatch-service
    participant NOT as notification-service

    CUR->>DLV: POST /v1/deliveries/{id}/cancel (reason)
    DLV->>DLV: state=unassigned
    DLV->>CDP: re-dispatch
    CDP->>CDP: find replacement courier
    alt replacement found in T
        CDP->>DLV: delivery.courier.assigned.v1 (new courier)
        DLV->>NOT: notify customer (new courier assigned)
    else no replacement
        DLV->>DLV: state=failed
        DLV->>NOT: notify customer (order couldn't be delivered)
        DLV->>FPI: trigger refund saga
    end
```

## Workflow: Failed Delivery (courier can't reach customer)

```mermaid
sequenceDiagram
    participant CUR as Courier
    participant DLV as delivery-service
    participant NOT as notification-service
    participant C as Customer
    participant SUP as support-service
    participant FPI as food-payment-integration-service

    CUR->>DLV: POST /v1/deliveries/{id}/failed (reason=customer_unreachable)
    DLV->>DLV: state=failed
    DLV->>NOT: notify customer (call us)
    NOT-->>C: SMS: "Courier is at your door, please call"
    Note over DLV: 5 min wait
    alt customer reaches support
        SUP->>DLV: redeliver (find new courier)
        DLV->>CDP: re-dispatch
    else timeout
        DLV->>NOT: notify customer (return to restaurant)
        DLV->>FPI: partial refund (per policy)
        FPI-->>DLV: refund
        DLV->>SUP: open ticket
    end
```

## Workflow: Wrong / Missing Items

```mermaid
sequenceDiagram
    participant C as Customer
    participant SUP as support-service
    participant FOR as food-order-service
    participant FPI as food-payment-integration-service
    participant RSM as restaurant-settlement-service
    participant NOT as notification-service

    C->>SUP: open ticket (order_id, issue=missing_item)
    SUP->>FOR: get order context
    FOR-->>SUP: order details
    SUP->>FPI: issue partial refund (per policy)
    FPI-->>SUP: refund.completed.v1
    SUP->>RSM: flag restaurant (quality)
    SUP-->>C: 200 OK
```

## Workflow: Promotion Redemption

```mermaid
sequenceDiagram
    participant CRT as cart-service
    participant PRM as promotion-service
    participant PRC as pricing-service
    participant FPI as food-payment-integration-service

    C->>CRT: apply code
    CRT->>PRM: validate(code, cart, customer)
    PRM-->>CRT: valid
    C->>CRT: checkout
    CRT->>PRC: quote (with promo)
    PRC-->>CRT: subtotal
    CRT->>FPI: include promo in payment intent
    FPI->>PRM: redeem (Idempotency-Key=cart:C:promo:CODE)
    PRM->>PRM: insert redemption
    PRM-->>FPI: ok
    FPI-->>CRT: payment.authorized.v1
```

## Workflow: Settlement Payout

```mermaid
sequenceDiagram
    participant RSM as restaurant-settlement-service
    participant LD as ledger-service
    participant PAY as payment-service
    participant MR as merchant-service

    Note over RSM: weekly payout job
    RSM->>RSM: aggregate merchant payable
    RSM->>LD: post payable
    LD-->>RSM: ledger.posted.v1
    RSM->>PAY: payout to merchant bank
    PAY-->>RSM: payout.completed
    RSM->>MR: merchant.payout.completed.v1
```

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Restaurant doesn't accept in T | Auto-reject, full refund, notify customer |
| Restaurant is offline | Cart shows "temporarily unavailable"; orders can't be placed |
| Item out of stock after cart | `menu.item.unavailable.v1` removes the item; cart re-quotes |
| Price changed | `menu.item.price.changed.v1` re-quotes; user must re-confirm |
| No courier available | Customer notified; order can be re-dispatched or refunded |
| Customer unreachable | 5 min wait; redeliver or refund per policy |
| Payment fails at checkout | Order not created; user re-tries with different method |
| Payment fails at capture | Refund issued; ticket opened |
| Restaurant cancels | Full refund; ticket opened for quality review |
| Courier cancels | Re-dispatch; if no replacement, refund |

## Acceptance Criteria (end-to-end)

- 95% of orders are accepted by restaurants within 5 minutes.
- 95% of accepted orders are delivered within the promised window.
- 99% of completed orders have a successful payment capture and
  merchant settlement.
- Restaurant cancel rate < 5% (target; varies by city).
- Courier cancel rate < 3% (target; varies by city).
- Average time from order placed to courier assigned: < 12 minutes.
