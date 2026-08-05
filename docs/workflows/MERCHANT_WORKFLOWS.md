# Merchant Workflows

This document covers merchant and restaurant onboarding, menu
management, branch operations, and settlement. Reflects the
**20-service architecture** consolidated 2026-08-05 per
[ADR-0017](../architecture/adrs/0017-20-service-architecture.md).

> For the **accounting view** of merchant settlement (marketplace VAT,
> commission revenue, merchant payable, payout, dispute debit) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Restaurant Settlement & Marketplace VAT".

## Workflow: Merchant Onboarding

```mermaid
sequenceDiagram
    participant OWN as Merchant Owner
    participant ID as identity-service
    participant RES as restaurant-service
    participant FS as file-service
    participant ADM as admin-service
    participant NOT as notification-service
    participant PAY as payment-service

    OWN->>ID: register (email, password)
    ID-->>OWN: kc_sub
    OWN->>RES: POST /v1/merchants (kc_sub, legal_name, tax_id, contacts, bank)
    RES->>FS: upload (trade_license, tax_cert, bank_letter, owner_id)
    FS-->>RES: file.uploaded.v1
    RES-->>OWN: 201 pending review
    ADM->>RES: review
    alt approved
        RES->>RES: state=approved
        RES->>PAY: merchant.created.v1
        PAY->>PAY: init payable account (in ledger)
        RES-->>NOT: notify owner
    else rejected
        RES-->>OWN: reason
        OWN->>RES: re-submit
    end
```

State machine for `merchant` (now in `restaurant.merchants`):

```mermaid
stateDiagram-v2
    [*] --> pending_review
    pending_review --> approved
    pending_review --> rejected
    pending_review --> expired
    rejected --> pending_review
    approved --> suspended
    approved --> closed
    suspended --> approved
    approved --> [*]
    closed --> [*]
```

## Workflow: Restaurant Onboarding (under a Merchant)

```mermaid
sequenceDiagram
    participant OWN as Merchant Owner
    participant RES as restaurant-service
    participant GEO as geolocation-service
    participant ADM as admin-service
    participant NOT as notification-service

    OWN->>RES: POST /v1/restaurants (merchant_id, name, cuisines, type)
    RES-->>OWN: 201 restaurant_id
    OWN->>RES: POST /v1/restaurants/{id}/branches (address, lat, lng, hours)
    RES->>GEO: geocode
    GEO-->>RES: normalized address + point
    RES-->>OWN: 201 branch_id
    OWN->>RES: POST /v1/restaurants/{id}/menu/categories
    OWN->>RES: POST /v1/restaurants/{id}/menu/products (name, price, modifiers, photo)
    RES-->>OWN: 201 menu_id
    OWN->>RES: POST /v1/restaurants/{id}/submit_for_review
    RES->>RES: state=pending_review
    ADM->>RES: review
    alt approved
        RES->>RES: state=approved
        RES-->>NOT: notify owner
        OWN->>RES: POST /v1/restaurants/{id}/online
        RES->>RES: state=online
    else rejected
        RES-->>OWN: reason
        OWN->>RES: re-submit
    end
```

## Workflow: Menu Management

```mermaid
sequenceDiagram
    participant OWN as Restaurant Owner
    participant RES as restaurant-service
    participant SR as search-service
    participant FOR as food-order-service

    OWN->>RES: POST /v1/restaurants/{id}/menu/categories
    OWN->>RES: POST /v1/restaurants/{id}/menu/products (name, price, modifiers, photo)
    RES->>RES: state=draft
    OWN->>RES: POST /v1/restaurants/{id}/menu/publish
    RES->>RES: state=published
    RES->>SR: menu.published.v1
    SR->>SR: index
    opt out of stock
        OWN->>RES: 86 the item
        RES->>RES: own inventory.out_of_stock.v1
        RES->>SR: reindex
        RES->>FOR: remove from active carts
    end
```

## Workflow: Restaurant Open / Close

```mermaid
sequenceDiagram
    participant OWN as Restaurant Owner
    participant RES as restaurant-service
    participant COS as courier-service
    participant SR as search-service
    participant FOR as food-order-service

    OWN->>RES: POST /v1/restaurants/{id}/online
    RES->>RES: state=online
    RES->>RES: any open branches? (within hours)
    RES->>COS: ready for orders
    RES->>SR: restaurant.online.v1
    RES->>FOR: re-enable checkouts
    Note over RES: scheduled close at end of hours
    RES->>RES: state=offline
    RES->>COS: stop accepting orders
    RES->>SR: restaurant.offline.v1
    RES->>FOR: block checkouts
```

## Workflow: Order Acceptance (Restaurant Operator View)

```mermaid
sequenceDiagram
    participant OP as Restaurant Operator
    participant FOR as food-order-service (own queue)
    participant NOT as notification-service
    participant PAY as payment-service (saga)
    participant COS as courier-service

    FOR->>FOR: own queue: food.order.placed.v1
    FOR->>OP: new order (push / sound)
    Note over FOR: 5-minute accept timer
    alt accept
        OP->>FOR: accept
        FOR->>FOR: emit food.order.accepted.v1
    else reject
        OP->>FOR: reject (reason)
        FOR->>FOR: emit food.order.rejected.v1
        FOR->>PAY: refund
        FOR->>NOT: notify customer
    end
    Note over FOR: kitchen prep
    OP->>FOR: start preparing
    FOR->>FOR: emit food.order.preparing.v1
    OP->>FOR: ready
    FOR->>FOR: emit food.order.ready.v1
    FOR->>COS: food.order.ready.v1 (for dispatch)
```

## Workflow: Branch Hours Management

```mermaid
sequenceDiagram
    participant OWN as Restaurant Owner
    participant RES as restaurant-service
    participant COS as courier-service
    participant FOR as food-order-service

    OWN->>RES: PUT /v1/restaurants/{id}/branches/{branch_id}/hours (weekly schedule + exceptions)
    RES->>RES: persist
    RES->>RES: branch.hours.changed.v1
    RES->>RES: recompute open/closed
    RES->>COS: branch.hours.changed.v1
    RES->>FOR: branch.hours.changed.v1
```

## Workflow: Restaurant Suspension (Quality / Compliance)

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant RES as restaurant-service
    participant COS as courier-service
    participant SR as search-service
    participant FOR as food-order-service
    participant NOT as notification-service
    participant OWN as Merchant Owner

    ADM->>RES: POST /v1/restaurants/{id}/suspend (reason)
    RES->>RES: state=suspended
    RES->>RES: cascade (all branches suspended)
    RES->>COS: stop accepting orders
    RES->>SR: remove from search
    RES->>FOR: block checkouts
    RES->>NOT: notify owner
    NOT-->>OWN: email + push
```

## Workflow: Restaurant Settlement Payout

See [PAYMENT_WORKFLOWS.md](PAYMENT_WORKFLOWS.md) — "Restaurant
Settlement". The merchant-facing view (in `payment-service`, which
absorbed `restaurant-settlement-service`):

```mermaid
sequenceDiagram
    participant OWN as Merchant Owner
    participant PAY as payment-service
    participant LD as ledger-service

    OWN->>PAY: GET /v1/merchants/{id}/payable?period=weekly
    PAY-->>OWN: payable balance
    OWN->>PAY: GET /v1/merchants/{id}/statement
    PAY-->>OWN: line items
    Note over PAY: weekly cron
    PAY->>LD: post payable
    PAY->>PAY: payout to bank
    PAY-->>PAY: ok
    PAY-->>OWN: merchant.payout.completed.v1 (push)
```

## Workflow: Restaurant Closure (Permanent)

```mermaid
sequenceDiagram
    participant OWN as Merchant Owner
    participant RES as restaurant-service
    participant COS as courier-service
    participant SR as search-service
    participant PAY as payment-service
    participant ADM as admin-service

    OWN->>RES: POST /v1/restaurants/{id}/close
    RES->>RES: close all branches
    RES->>RES: state=closed
    RES->>COS: stop accepting
    RES->>SR: remove from search
    RES->>ADM: open closure review
    ADM->>PAY: final payout
    PAY->>PAY: payout all remaining payable
    PAY-->>OWN: payout.completed
```

## Failure Paths Summary

| Failure | Handling |
|---------|----------|
| Restaurant doesn't accept in T | Auto-reject, customer refunded |
| Restaurant is offline unexpectedly | Orders blocked; restaurant auto-set offline for T |
| Branch hours don't match reality | Reconciliation job flags drift; admin reviews |
| Menu item unavailable | Replaced in cart; user notified |
| Payout fails | Bank details updated; retry |
| Quality issues | Suspension; review; reinstatement after action plan |

## Acceptance Criteria

- Restaurant can complete onboarding in < 48 hours of all documents
  being uploaded.
- 99% of menu changes are visible in the customer app within
  30 seconds.
- 99% of order acceptance decisions happen within 5 minutes.
- 100% of approved payouts are confirmed within 1 business day.