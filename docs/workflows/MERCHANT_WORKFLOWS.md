# Merchant Workflows

This document covers merchant and restaurant onboarding, menu
management, branch operations, and settlement.

> For the **accounting view** of merchant settlement (marketplace VAT,
> commission revenue, merchant payable, payout, dispute debit) see
> [`ACCOUNTING_WORKFLOWS.md`](ACCOUNTING_WORKFLOWS.md) — "Workflow:
> Restaurant Settlement & Marketplace VAT".

## Workflow: Merchant Onboarding

```mermaid
sequenceDiagram
    participant OWN as Merchant Owner
    participant ID as identity-service
    participant MER as merchant-service
    participant FS as file-service
    participant ADM as admin-service
    participant NOT as notification-service
    participant RSM as restaurant-settlement-service

    OWN->>ID: register (email, password)
    ID-->>OWN: kc_sub
    OWN->>MER: POST /v1/merchants (kc_sub, legal_name, tax_id, contacts, bank)
    MER->>FS: upload (trade_license, tax_cert, bank_letter, owner_id)
    FS-->>MER: file.uploaded.v1
    MER-->>OWN: 201 pending review
    ADM->>MER: review
    alt approved
        MER->>MER: state=approved
        MER->>RSM: merchant.created.v1
        RSM->>RSM: init payable account (in ledger)
        MER-->>NOT: notify owner
    else rejected
        MER-->>OWN: reason
        OWN->>MER: re-submit
    end
```

State machine for `merchant`:

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
    participant MER as merchant-service
    participant RES as restaurant-service
    participant BRH as branch-service
    participant GEO as geolocation-service
    participant MN as menu-service
    participant ADM as admin-service
    participant NOT as notification-service

    OWN->>RES: POST /v1/restaurants (merchant_id, name, cuisines, type)
    RES-->>OWN: 201 restaurant_id
    OWN->>BRH: POST /v1/branches (restaurant_id, address, lat, lng, hours)
    BRH->>GEO: geocode
    GEO-->>BRH: normalized address + point
    BRH-->>OWN: 201 branch_id
    OWN->>MN: POST /v1/menus (restaurant_id, categories, products, modifiers)
    MN-->>OWN: 201 menu_id
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
    participant MN as menu-service
    participant INV as inventory-service
    participant SR as search-service
    participant CRT as cart-service

    OWN->>MN: POST /v1/menus/{id}/categories
    OWN->>MN: POST /v1/menus/{id}/products (name, price, modifiers, photo)
    MN->>MN: state=draft
    OWN->>MN: POST /v1/menus/{id}/publish
    MN->>MN: state=published
    MN->>SR: menu.published.v1
    SR->>SR: index
    opt out of stock
        OWN->>INV: 86 the item
        INV->>MN: inventory.item.out_of_stock.v1
        MN->>SR: reindex
        MN->>CRT: remove from active carts
    end
```

## Workflow: Restaurant Open / Close

```mermaid
sequenceDiagram
    participant OWN as Restaurant Owner
    participant RES as restaurant-service
    participant BRH as branch-service
    participant CDP as courier-dispatch-service
    participant SR as search-service
    participant CRT as cart-service

    OWN->>RES: POST /v1/restaurants/{id}/online
    RES->>RES: state=online
    RES->>BRH: any open branches? (within hours)
    BRH-->>RES: open branch
    RES->>CDP: ready for orders
    RES->>SR: restaurant.online.v1
    RES->>CRT: re-enable checkouts
    Note over RES: scheduled close at end of hours
    RES->>RES: state=offline
    RES->>CDP: stop accepting orders
    RES->>SR: restaurant.offline.v1
    RES->>CRT: block checkouts
```

## Workflow: Order Acceptance (Restaurant Operator View)

```mermaid
sequenceDiagram
    participant OP as Restaurant Operator
    participant ROM as restaurant-order-mgmt-service
    participant FOR as food-order-service
    participant NOT as notification-service
    participant FPI as food-payment-integration-service

    FOR->>ROM: food.order.placed.v1
    ROM->>OP: new order (push / sound)
    Note over ROM: 5-minute accept timer
    alt accept
        OP->>ROM: accept
        ROM->>FOR: food.order.accepted.v1
    else reject
        OP->>ROM: reject (reason)
        ROM->>FOR: food.order.rejected.v1
        FOR->>FPI: refund
        FOR->>NOT: notify customer
    end
    Note over ROM: kitchen prep
    OP->>ROM: start preparing
    ROM->>FOR: food.order.preparing.v1
    OP->>ROM: ready
    ROM->>FOR: food.order.ready.v1
    FOR->>CDP: food.order.ready.v1 (for dispatch)
```

## Workflow: Branch Hours Management

```mermaid
sequenceDiagram
    participant OWN as Restaurant Owner
    participant BRH as branch-service
    participant RES as restaurant-service
    participant CDP as courier-dispatch-service
    participant CRT as cart-service

    OWN->>BRH: PUT /v1/branches/{id}/hours (weekly schedule + exceptions)
    BRH->>BRH: persist
    BRH->>RES: branch.hours.changed.v1
    RES->>RES: recompute open/closed
    BRH->>CDP: branch.hours.changed.v1
    BRH->>CRT: branch.hours.changed.v1
```

## Workflow: Restaurant Suspension (Quality / Compliance)

```mermaid
sequenceDiagram
    participant ADM as admin-service
    participant RES as restaurant-service
    participant BRH as branch-service
    participant CDP as courier-dispatch-service
    participant SR as search-service
    participant CRT as cart-service
    participant NOT as notification-service

    ADM->>RES: POST /v1/restaurants/{id}/suspend (reason)
    RES->>RES: state=suspended
    RES->>BRH: cascade
    BRH->>BRH: all branches suspended
    RES->>CDP: stop accepting orders
    RES->>SR: remove from search
    RES->>CRT: block checkouts
    RES->>NOT: notify owner
    NOT-->>OWN: email + push
```

## Workflow: Restaurant Settlement Payout

See [PAYMENT_WORKFLOWS.md](PAYMENT_WORKFLOWS.md) — "Restaurant
Settlement". The merchant-facing view:

```mermaid
sequenceDiagram
    participant OWN as Merchant Owner
    participant RSM as restaurant-settlement-service
    participant PAY as payment-service
    participant LD as ledger-service

    OWN->>RSM: GET /v1/settlements?period=weekly
    RSM-->>OWN: payable balance
    OWN->>RSM: GET /v1/settlements/{id}
    RSM-->>OWN: line items
    Note over RSM: weekly cron
    RSM->>LD: post payable
    RSM->>PAY: payout to bank
    PAY-->>RSM: ok
    RSM-->>OWN: merchant.payout.completed.v1 (push)
```

## Workflow: Restaurant Closure (Permanent)

```mermaid
sequenceDiagram
    participant OWN as Merchant Owner
    participant MER as merchant-service
    participant RES as restaurant-service
    participant BRH as branch-service
    participant CDP as courier-dispatch-service
    participant SR as search-service
    participant RSM as restaurant-settlement-service
    participant ADM as admin-service

    OWN->>RES: POST /v1/restaurants/{id}/close
    RES->>BRH: close all branches
    RES->>RES: state=closed
    RES->>CDP: stop accepting
    RES->>SR: remove from search
    RES->>ADM: open closure review
    ADM->>RSM: final payout
    RSM->>PAY: payout all remaining payable
    RSM-->>OWN: payout.completed
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
