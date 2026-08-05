# Context Map

A context map describes the **relationships between bounded contexts** using
the standard DDD patterns: customer/supplier, conformist, anti-corruption
layer (ACL), shared kernel, open-host/PL, partnership, and so on. It
captures **how** contexts interact, not just whether they do.

## Relationship Patterns in Use

| Pattern | Meaning | Where used in this platform |
|---------|---------|----------------------------|
| **Customer / Supplier** | Upstream (supplier) provides a service; downstream (customer) depends on it. The supplier's team commits to stability. | All inter-service APIs |
| **Conformist** | Downstream accepts the upstream's model as-is, without translation. | Internal microservices consuming events |
| **Anti-Corruption Layer (ACL)** | Downstream translates the upstream's model into its own model. | Adapter services wrapping external providers (`payment-service`, `communication-gateway-service`, `geolocation-service`) |
| **Open-Host / Published Language (PL)** | Upstream publishes a stable, documented protocol. | REST APIs, event schema catalog |
| **Partnership** | Two contexts succeed or fail together; teams co-evolve. | `trip-service` ↔ `dispatch-service`; `food-order-service` ↔ `restaurant-order-mgmt-service` |
| **Shared Kernel** | Two contexts share a subset of the model deliberately. | The event envelope (`event_id`, `occurred_at`, `correlation_id`, `causation_id`, `version`, `tenant_id`) — shared by all publishers and consumers, versioned as one schema. |
| **Separate Ways** | Two contexts are intentionally not integrated. | Reporting-side caches for OLAP vs OLTP |

## Context Map Diagram

```mermaid
graph LR
    subgraph Identity["Identity & Profile"]
        ID[identity-service]
        UP[user-profile-service]
        CST[customer-service]
        DRV[driver-service]
        CUR[courier-service]
        VEH[vehicle-service]
        ADR[address-service]
    end

    subgraph Geo["Geospatial"]
        GEO[geolocation-service]
        ZON[zone-service]
    end

    subgraph Rules["Pricing & Rules"]
        PRC[pricing-service]
        PRM[promotion-service]
        LOY[loyalty-service]
        TAX[tax-service]
        REV[review-rating-service]
    end

    subgraph Platform["Platform"]
        GW[api-gateway]
        NOT[notification-service]
        CGS[communication-gateway-service]
        CFG[configuration-service]
        FF[feature-flag-service]
        FIL[file-service]
        SRH[search-service]
        AUD[audit-service]
        ANA[analytics-service]
        ADM[admin-service]
        SUP[support-service]
        FRD[fraud-risk-service]
        REP[reporting-service]
    end

    subgraph Ride["Ride-Hailing"]
        RQR[ride-request-service]
        TRP[trip-service]
        DAV[driver-availability-service]
        DLO[driver-location-service]
        DSP[dispatch-service]
        ETA[eta-routing-service]
        RPI[ride-payment-integration-service]
        DEN[driver-earnings-service]
        DIN[driver-incentive-service]
        SCH[scheduled-ride-service]
        SFE[ride-safety-service]
        RHX[ride-history-service]
    end

    subgraph Food["Food Marketplace"]
        MER[merchant-service]
        RES[restaurant-service]
        BRH[branch-service]
        RST[restaurant-staff-service]
        MNU[menu-service]
        INV[inventory-service]
        CRT[cart-service]
        CKO[checkout-service]
        FOR[food-order-service]
        ROM[restaurant-order-mgmt-service]
    end

    subgraph Delivery["Delivery & Couriers"]
        CDP[courier-dispatch-service]
        DLV[delivery-service]
        CTR[courier-tracking-service]
        CEN[courier-earnings-service]
    end

    subgraph Financial["Financial"]
        PAY[payment-service]
        WLT[wallet-service]
        LDG[ledger-service]
        FPI[food-payment-integration-service]
        RSM[restaurant-settlement-service]
    end

    %% Customer/supplier edges (selected; full list in MICROSERVICES_MAP.md)
    GW --> ID
    GW --> CST
    GW --> DRV
    GW --> CUR
    GW --> RQR
    GW --> FOR

    CST --> ID
    DRV --> ID
    DRV --> VEH
    CUR --> ID
    CUR --> VEH

    RQR --> CST
    RQR --> PRC
    RQR --> DSP
    TRP --> DRV
    TRP --> ETA
    DSP --> DAV
    DSP --> DLO

    RPI --> PAY
    RPI --> WLT
    RPI --> LDG
    RPI --> DEN
    DEN --> WLT
    DEN --> LDG

    FOR --> MER
    FOR --> RES
    FOR --> BRH
    CRT --> MNU
    CRT --> PRC
    CRT --> PRM
    CKO --> CRT
    CKO --> PAY
    CKO --> ADR
    FOR --> CKO

    CDP --> CTR
    CDP --> CUR
    DLV --> CUR
    DLV --> FOR

    FPI --> PAY
    FPI --> WLT
    FPI --> LDG
    FPI --> RSM
    FPI --> CEN
    RSM --> LDG
    RSM --> PAY

    NOT --> CGS

    %% Open-host / PL edges
    ID -. "PL: events identity.*" .-> CST
    ID -. "PL: events identity.*" .-> DRV
    ID -. "PL: events identity.*" .-> CUR
    PAY -. "PL: events payment.*" .-> RPI
    PAY -. "PL: events payment.*" .-> FPI
    FOR -. "PL: events food.order.*" .-> CDP
    FOR -. "PL: events food.order.*" .-> ROM

    %% ACL edges
    PAY == "ACL: provider schema" ==> EXT_PAY[Payment Provider]
    CGS == "ACL: provider schema" ==> EXT_SMS[SMS Provider]
    CGS == "ACL: provider schema" ==> EXT_EMAIL[Email Provider]
    GEO == "ACL: provider schema" ==> EXT_MAP[Map Provider]

    %% Shared kernel
    classDef sk stroke:#aa00aa,stroke-width:2px,stroke-dasharray:5 5;
    class ID,UP,CST,DRV,CUR,VEH,ADR sk
```

Legend: solid arrows are synchronous calls; dashed arrows are async event
subscriptions; double-line arrows are anti-corruption layers to external
systems. Purple dashed classes are the shared-kernel "event envelope" set
followed by all publishers.

## Upstream / Downstream Stability Contracts

For each customer/supplier relationship, the supplier commits to:

1. **No breaking changes within a major version.** New fields may be added
   to a response; existing fields are not removed or repurposed.
2. **Versioning.** Breaking changes are released as `/v2` URI and
   `event.v2` in parallel; old version supported for ≥ 6 months with
   deprecation headers.
3. **SLO.** Each service declares an availability SLO in its `SRS.md`.
   Critical supplier services commit to 99.95%+.
4. **Runbook.** Every service publishes a runbook in its `INTEGRATION.md`
   with the failure modes and the consumer's expected response.
5. **Status page.** Each service reports its health to a central status
   service consumed by `api-gateway` and the admin console.

## Anti-Corruption Layers (Where and Why)

| ACL Service | Wraps | What it translates | Why it exists |
|-------------|-------|--------------------|---------------|
| `identity-service` | Keycloak | Keycloak's `UserRepresentation`, group/role model, token introspection | Keycloak's schema is implementation-specific; domain services want a stable, simplified `Identity` aggregate |
| `payment-service` | Payment provider | Provider-specific authorize/capture/refund/webhook schemas | Provider schema may change; we hide that behind our own `PaymentIntent` aggregate and versioned events |
| `communication-gateway-service` | SMS / Email / Push providers | Each provider's send-status, delivery-status, opt-out semantics | Providers have inconsistent semantics (e.g. push delivery receipts) |
| `geolocation-service` | Map provider | Provider's geocode / ETA / route response | Provider schemas vary; we normalize to a stable Geocode/ETA/Route aggregate |
| `eta-routing-service` | Map provider (read-side) | ETA / route response | Separate from `geolocation-service` so the read-heavy trip ETA path scales independently |
| `feature-flag-service` | (internal) | Flag resolution rules, segment matching, rollout % | Hides rule-engine complexity from consumers |

## Partnership Relationships

These pairs are tightly coupled and SHOULD be co-owned by the same team or
have explicit joint on-call:

- `ride-request-service` ↔ `dispatch-service` — the booking flow cannot
  succeed without a successful match.
- `food-order-service` ↔ `restaurant-order-mgmt-service` — the order
  cannot be fulfilled without restaurant acceptance.
- `food-order-service` ↔ `courier-dispatch-service` — pickup cannot happen
  without a courier.
- `trip-service` ↔ `ride-payment-integration-service` — trip cannot be
  financially closed without payment capture.
- `food-order-service` ↔ `food-payment-integration-service` — order
  cannot be financially closed without payment.

For each partnership, the teams share:

- A common on-call rotation.
- Joint design reviews for changes on either side.
- A shared "flow health" dashboard in `reporting-service`.

## Published Languages (Stable Schemas)

| Published language | Owner | Format | Versioning rule |
|--------------------|-------|--------|------------------|
| Event envelope | Platform | JSON | Major bump on envelope change; minor bump on optional field addition |
| Error envelope | Platform | JSON | Same as event envelope |
| Audit event | Platform | JSON | Major bump on breaking change to required fields |
| Address (geocoded) | `address-service` | JSON | Bump `v2` on field rename/removal |
| Money | `pricing-service` | JSON `{ amount_minor: int, currency: ISO4217 }` | Stable; new currencies are additive |
| PriceQuote | `pricing-service` | JSON | Additive only within a major; `v2` for model change |
| PaymentIntent | `payment-service` | JSON | Additive only within a major |
| Trip / FoodOrder aggregates | Respective services | JSON | Additive only within a major |

## Where Coupling Is Deliberately Avoided

- `driver-service` does **not** call `trip-service`. It learns trip
  events via Kafka and never reaches into trip state.
- `restaurant-service` does **not** call `menu-service`. Menu updates
  arrive as `menu.updated.v1` events.
- `payment-service` does **not** know about `trip-service` or
  `food-order-service`. It accepts a generic `PaymentIntent` with
  references and emits generic money events.
- `reporting-service` does **not** mutate any other service. It is a
  read-side projection.

## Where Coupling Is Unavoidable (and How We Manage It)

- The **event envelope** is a shared kernel. Versioned in
  [`EVENT_ARCHITECTURE.md`](EVENT_ARCHITECTURE.md).
- The **`Money` shape** is a shared kernel. Defined in
  [`API_STANDARDS.md`](API_STANDARDS.md) and used by all financial
  services.
- The **`Address` shape** is a shared kernel. Defined in
  `address-service/README.md` and reused as `pickup_address` /
  `dropoff_address` / `delivery_address` in ride and food flows.
- The **`Identity` shape** is a shared kernel. Defined in
  `identity-service/README.md` and reused as `customer_id`,
  `driver_id`, `courier_id`, `merchant_id` across services.
