# Food Order Workflows

This document covers the end-to-end flows for the food delivery
product. Reflects the **20-service architecture** consolidated
2026-08-05 per [ADR-0017](../architecture/adrs/0017-20-service-architecture.md).
Per-service state machines are in each service's `WORKFLOWS.md`.

> For the **accounting view** of food order transactions (customer
> transaction recognition; merchant payable; courier earning;
> marketplace VAT; expense) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Customer Transaction Recognition (Ride / Food)" and "Workflow:
> Restaurant Settlement & Marketplace VAT".

## Actors and Services

| Actor | Services they touch directly |
|-------|------------------------------|
| Customer | `food-order-service` (cart, checkout, order, food reviews), `payment-service`, `customer-service` |
| Restaurant operator | `food-order-service` (queue), `restaurant-service` (menu / branch / staff / inventory) |
| Courier | `courier-service` (dispatch / tracking / delivery / earnings) |
| System | `restaurant-service` (merchant / branch / menu / inventory / staff), `pricing-service` (pricing + tax + promotion + loyalty rules), `notification-service`, `payment-service` (food saga + merchant settlement + COD), `reporting-service` |

## Workflow: Customer Places a Food Order (Happy Path)

```mermaid
sequenceDiagram
    participant C as Customer
    participant FOR as food-order-service
    participant RES as restaurant-service
    participant PRC as pricing-service
    participant PAY as payment-service
    participant COS as courier-service
    participant NOT as notification-service

    C->>FOR: POST /v1/carts (items)
    FOR->>RES: get menu
    RES-->>FOR: menu
    FOR->>PRC: quote
    PRC->>PRC: apply promotion + tax
    PRC-->>FOR: subtotal
    C->>FOR: PATCH /v1/carts/{id} (address, tip)
    C->>FOR: POST /v1/checkouts (cart_id, payment_method)
    FOR->>PAY: authorize (Idempotency-Key=cart:C:auth)
    PAY-->>FOR: payment.authorized.v1
    FOR->>FOR: create order (state=placed)
    FOR-->>C: 201 order
    FOR->>FOR: own queue accepts (within T minutes)
    FOR-->>RES: notify restaurant
    RES-->>FOR: accept
    FOR->>FOR: state=accepted -> preparing
    FOR->>FOR: own queue marks preparing
    Note over FOR: kitchen marks ready
    FOR->>FOR: own queue marks ready
    FOR->>COS: food.order.ready.v1
    COS->>COS: find courier
    COS->>COS: own delivery aggregate created
    COS->>FOR: delivery.courier.assigned.v1
    COS->>FOR: delivery.pickup.v1
    COS->>COS: state=in_transit
    COS->>COS: state=delivered (proof)
    COS->>FOR: delivery.completed.v1
    FOR->>PAY: delivery.completed.v1
    PAY->>PAY: capture (Idempotency-Key=order:O:cap)
    PAY-->>PAY: payment.captured.v1
    PAY->>PAY: courier earning accrual
    PAY->>PAY: merchant settlement accrual
    PAY-->>FOR: food.payment.completed.v1
    FOR->>FOR: state=delivered
    FOR->>NOT: notify customer
    NOT-->>C: push: "Order delivered"
    FOR->>FOR: food review prompt
    FOR-->>C: push: "How was your order?"
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
    participant FOR as food-order-service
    participant RES as restaurant-service
    participant PRC as pricing-service

    C->>FOR: POST /v1/carts (items)
    FOR->>RES: validate items, prices
    RES-->>FOR: ok
    FOR->>FOR: persist (state=active)
    loop as user edits
        C->>FOR: PATCH /v1/carts/{id} (add/remove items)
        FOR->>RES: re-validate
        FOR->>FOR: re-quote
    end
    opt apply promotion
        C->>FOR: POST /v1/carts/{id}/promotions { code }
        FOR->>PRC: validate
        PRC-->>FOR: ok
        FOR->>FOR: apply
    end
    C->>FOR: POST /v1/carts/{id}/checkout
    FOR->>FOR: create checkout session
    Note over FOR: 30 min idle -> cart.abandoned.v1
```

If the menu item becomes unavailable while the cart is active,
`menu.item.unavailable.v1` (own producer) is consumed; the item is
removed from the cart and the user is notified.

If the price changes, `menu.item.price.changed.v1` (own producer)
triggers a re-quote; the cart is updated and the user is notified
of the diff.

## Workflow: Restaurant Acceptance (with timer)

```mermaid
sequenceDiagram
    participant FOR as food-order-service
    participant RES as restaurant-service
    participant NOT as notification-service
    participant C as Customer
    participant PAY as payment-service (saga)

    FOR->>FOR: own queue: food.order.placed.v1
    Note over FOR: 5-minute accept timer
    alt restaurant accepts
        RES-->>FOR: accept
        FOR->>FOR: emit food.order.accepted.v1
    else restaurant rejects
        RES-->>FOR: reject (reason)
        FOR->>FOR: emit food.order.rejected.v1
        FOR->>PAY: trigger refund saga
        PAY->>PAY: refund authorization
        FOR->>NOT: notify customer
        NOT-->>C: push: "Restaurant can't fulfill, refunded"
    else timer expires
        FOR->>FOR: emit food.order.rejected.v1 (reason=auto_reject)
        FOR->>PAY: trigger refund
        FOR->>NOT: notify customer
    end
```

## Workflow: Courier Dispatch and Delivery

```mermaid
sequenceDiagram
    participant FOR as food-order-service
    participant COS as courier-service
    participant CUR as Courier
    participant NOT as notification-service

    FOR->>COS: food.order.ready.v1
    COS->>COS: search available couriers
    COS->>COS: get current locations (own stream)
    COS->>CUR: offer (push)
    CUR-->>COS: accept
    COS->>COS: own delivery aggregate created
    CUR->>COS: POST /v1/deliveries/{id}/arrived (at restaurant)
    COS->>NOT: notify restaurant (courier arrived)
    CUR->>COS: POST /v1/deliveries/{id}/pickup
    COS->>COS: state=picked_up
    COS->>NOT: notify customer (courier on the way)
    CUR->>COS: POST /v1/deliveries/{id}/complete (proof)
    COS->>COS: state=delivered
    COS->>NOT: notify customer (delivered)
```

## Workflow: Customer Cancellation (with policy)

```mermaid
sequenceDiagram
    participant C as Customer
    participant FOR as food-order-service
    participant PRC as pricing-service
    participant PAY as payment-service
    participant NOT as notification-service

    C->>FOR: POST /v1/orders/{id}/cancellation
    FOR->>PRC: calculate cancellation fee
    PRC-->>FOR: fee
    alt before restaurant accept
        FOR->>PAY: full refund
        PAY-->>FOR: payment.refund.completed.v1
        FOR->>FOR: state=cancelled
        FOR-->>C: 200 OK
    else after accept, before ready
        FOR->>PAY: partial refund (less restaurant cancel fee)
        PAY-->>FOR: payment.refund.completed.v1
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
    participant FOR as food-order-service
    participant PAY as payment-service
    participant NOT as notification-service
    participant C as Customer
    participant ADM as admin-service (support module)

    RES->>FOR: POST /v1/orders/{id}/cancel (reason)
    FOR->>FOR: emit food.order.rejected.v1 (reason=restaurant_cancel)
    FOR->>PAY: full refund
    PAY-->>FOR: payment.refund.completed.v1
    FOR->>NOT: notify customer
    NOT-->>C: push: "Restaurant cancelled, full refund"
    FOR->>ADM: open ticket (for restaurant quality review, via support.admin)
```

## Workflow: Courier Cancellation / Reassignment

```mermaid
sequenceDiagram
    participant CUR as Courier
    participant COS as courier-service
    participant NOT as notification-service

    CUR->>COS: POST /v1/deliveries/{id}/cancel (reason)
    COS->>COS: state=unassigned
    COS->>COS: re-dispatch
    alt replacement found in T
        COS->>COS: delivery.courier.assigned.v1 (new courier)
        COS->>NOT: notify customer (new courier assigned)
    else no replacement
        COS->>COS: state=failed
        COS->>NOT: notify customer (order couldn't be delivered)
        COS->>PAY: trigger refund saga
    end
```

## Workflow: Failed Delivery (courier can't reach customer)

```mermaid
sequenceDiagram
    participant CUR as Courier
    participant COS as courier-service
    participant NOT as notification-service
    participant C as Customer
    participant ADM as admin-service (support module)
    participant PAY as payment-service (saga)

    CUR->>COS: POST /v1/deliveries/{id}/failed (reason=customer_unreachable)
    COS->>COS: state=failed
    COS->>NOT: notify customer (call us)
    NOT-->>C: SMS: "Courier is at your door, please call"
    Note over COS: 5 min wait
    alt customer reaches support
        ADM->>COS: redeliver (find new courier)
        COS->>COS: re-dispatch
    else timeout
        COS->>NOT: notify customer (return to restaurant)
        COS->>PAY: partial refund (per policy)
        PAY-->>COS: payment.refund.completed.v1
        COS->>ADM: open ticket
    end
```

## Workflow: Wrong / Missing Items

```mermaid
sequenceDiagram
    participant C as Customer
    participant ADM as admin-service (support module)
    participant FOR as food-order-service
    participant PAY as payment-service
    participant NOT as notification-service

    C->>ADM: open ticket (order_id, issue=missing_item)
    ADM->>FOR: get order context
    FOR-->>ADM: order details
    ADM->>PAY: issue partial refund (per policy)
    PAY-->>ADM: payment.refund.completed.v1
    ADM->>FOR: flag restaurant (quality)
    ADM-->>C: 200 OK
```

## Workflow: Promotion Redemption

```mermaid
sequenceDiagram
    participant FOR as food-order-service
    participant PRC as pricing-service
    participant PAY as payment-service

    C->>FOR: apply code
    FOR->>PRC: validate(code, cart, customer)
    PRC-->>FOR: valid
    C->>FOR: checkout
    FOR->>PRC: quote (with promo)
    PRC-->>FOR: subtotal
    FOR->>PAY: include promo in payment intent
    PAY->>PRC: redeem (Idempotency-Key=cart:C:promo:CODE)
    PRC->>PRC: insert redemption
    PRC-->>PAY: ok
    PAY-->>FOR: payment.authorized.v1
```

## Workflow: Settlement Payout (COD + non-COD)

```mermaid
sequenceDiagram
    participant PAY as payment-service
    participant LD as ledger-service
    participant RES as restaurant-service

    Note over PAY: weekly payout job
    PAY->>PAY: aggregate merchant payable
    PAY->>LD: post payable
    LD-->>PAY: ledger.posted.v1
    PAY->>PAY: payout to merchant bank
    PAY-->>PAY: payout.completed
    PAY->>RES: merchant.payout.completed.v1
```

For **COD** orders, the courier marks the order as collected
(`POST /v1/orders/{id}/cod/mark-collected`) which posts the
merchant payable on pickup. See `payment-service/README.md` A.7.

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
| Payment fails at capture | Refund issued; ticket opened (via admin-service support module) |
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