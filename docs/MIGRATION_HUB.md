# Service Migration Hub

This hub is the **single authoritative map** for the consolidation
described in
[ADR-0017: 20-Service Architecture](architecture/adrs/0017-20-service-architecture.md)
(which supersedes the earlier half-step
[ADR-0016](architecture/adrs/0016-service-domain-consolidation.md)).

The platform's active microservices catalog now contains **20
services**. **38 services have been removed** — their directories
were deleted after their documentation, schemas, events,
endpoints, and operational details were absorbed into the 20
survivor services and into the per-row appendix table in 2 of
this hub.

> This hub and the five unchanged services'
> `Removed predecessor capability` appendices inside each absorbing
> service are the only places where the removed service names
> appear. Any deep link, internal note, or external reference to a
> removed service resolves here or inside the absorbing service's
> appendix. The compatibility window for old event topics, old REST
> paths, and old schema names is **at least six calendar months**
> from 2026-08-05.

## 1. Active service count

| When | Active services | Removed | Reference |
|------|----------------:|--------:|-----------|
| Before 2026-08-05 (historical) | 58 | 0 | [MICROSERVICES_MAP "Service Count Summary"](architecture/MICROSERVICES_MAP.md#service-count-summary) historical |
| After ADR-0016 (interim) | 44 | 14 | superseded |
| After  ADR-0017 (final)   | 20 | 38 | this hub + ADR-0017 |

## 2. Consolidation matrix

Each row links to the absorbing service's "Removed predecessor
capability" appendix where the absorbed schema, events, endpoints,
and workflows are appended (preserving original section numbers).

### 2.1 customer-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``customer-service` (cross-persona profile)` | cross-persona user profile (display name, avatar, locale, notification prefs) | [3.1](#31-user-profile) |
| ``customer-service` (addresses)` | saved addresses (geocoded, normalised, tagged) | [3.2](#32-address) |

`customer-service` also exposes the **loyalty account** (the
per-user balance and earn / burn history). The loyalty pricing
**rules** are owned by `pricing-service` (see 2.5).

### 2.2 driver-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``driver-service` (availability)` | driver online state machine | [3.3](#33-driver-availability) |
| ``driver-service` (location)` | high-frequency driver location stream | [3.4](#34-driver-location) |
| ``driver-service` (dispatch)` | ride matching + assignment ledger | [3.5](#35-dispatch) |
| ``driver-service` (incentives)` | quests / bonuses / surge guarantees / eligibility | [3.6](#36-driver-incentive) |
| ``driver-service` (vehicles)` | vehicles (plates, registration, insurance, inspection) | [3.7](#37-vehicle) |

### 2.3 trip-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``trip-service` (ride-request)` | ride booking aggregate (requested / matched / cancelled / expired) | [3.8](#38-ride-request) |
| ``trip-service` (scheduled)` | scheduled (future-dated) ride jobs | [3.9](#39-scheduled-ride) |
| ``trip-service` (safety)` | SOS, share-trip, audio recording, incident reports | [3.10](#310-ride-safety) |
| ``trip-service` (history)` | denormalised read model of trips, payments, reviews | [3.11](#311-ride-history) |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` (trip projection) | trip review slice | [3.12](#312-review-rating) |

### 2.4 pricing-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``pricing-service` (tax)` | tax jurisdiction rules, exemptions, product tax codes | [3.13](#313-tax) |
| ``pricing-service` (promotion)` | coupons, campaigns, redemption rules, redemption history | [3.14](#314-promotion) |
| ``pricing-service` (loyalty rules) / `customer-service` (account)` (rule capability only) | earn / burn / tier math, eligibility, promo-binding | [3.15](#315-loyalty-rules) |

> The **loyalty account** (per-user balance, history) is owned by
> `customer-service`; see 2.1. `pricing-service` owns the rules.

### 2.5 restaurant-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``restaurant-service` (merchant)` | merchant (legal entity) | [3.16](#316-merchant) |
| ``restaurant-service` (branch)` | branches (physical locations) | [3.17](#317-branch) |
| ``restaurant-service` (menu)` | categories, products, modifiers, add-ons, pricing | [3.18](#318-menu) |
| ``restaurant-service` (inventory)` | stock counts, time-bound availability, 86-list | [3.19](#319-inventory) |
| ``restaurant-service` (staff)` | staff invitations, role assignments, devices | [3.20](#320-restaurant-staff) |

### 2.6 food-order-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``food-order-service` (cart)` | shopping cart aggregate | [3.21](#321-cart) |
| ``food-order-service` (checkout)` | checkout session aggregate | [3.22](#322-checkout) |
| ``food-order-service` (queue)` | restaurant-side queue, accept/reject timer, prep state | [3.23](#323-restaurant-order-mgmt) |
| ``trip-service` / `food-order-service` / `search-service` (review projections)` (food projection) | food review slice | [3.12](#312-review-rating) |

### 2.7 courier-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``courier-service` (dispatch)` | courier matching + assignment ledger | [3.24](#324-courier-dispatch) |
| ``courier-service` (tracking)` | high-frequency courier location stream | [3.25](#325-courier-tracking) |
| ``courier-service` (delivery)` | delivery aggregate (assigned → delivered / failed) | [3.26](#326-delivery) |

### 2.8 payment-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``payment-service` (ride saga)` | ride payment saga orchestration | [3.27](#327-ride-payment-integration) |
| ``payment-service` (food saga)` | food payment saga orchestration | [3.28](#328-food-payment-integration) |
| ``payment-service` (wallet)` | customer wallet, holds, top-ups | [3.29](#329-wallet) |
| ``payment-service` (driver earnings)` | driver earnings + withdrawals | [3.30](#330-driver-earnings) |
| ``payment-service` (courier earnings)` | courier earnings + withdrawals | [3.31](#331-courier-earnings) |
| ``payment-service` (merchant settlement)` | merchant payable, payout runs, disputes, COD money | [3.32](#332-restaurant-settlement) |

> **COD payment state** is handled inside `payment-service` (the
> same payment-intents + captures flow with a `kind=cod` modifier
> that posts to the merchant payable on pickup). The platform's
> four-layer accounting model still applies.

### 2.9 geolocation-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``geolocation-service` (ETA/routing)` | ETA + route polylines + distance + alternatives | [3.33](#333-eta-routing) |
| ``geolocation-service` (zones)` | cities, service zones, surge zones, restricted zones | [3.34](#334-zone) |

### 2.10 notification-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``notification-service` (provider ACL)` | provider anti-corruption layer (SMS / email / push / WhatsApp) | [3.35](#335-communication-gateway) |

> The **immutable notification template-version snapshot chain**
> remains append-only and is owned by `notification-service`. The
> absorbed provider layer is re-mounted inside this service and
> continues to call the same providers with the same
> `template_version_snapshot_id` value.

### 2.11 configuration-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``configuration-service` (flags)` | flag definitions, overrides, rollout percentages | [3.36](#336-feature-flag) |

### 2.12 reporting-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``reporting-service` (data lake)` | event ingestion pipeline for the data lake | [3.37](#337-analytics) |

### 2.13 admin-service

| Removed service | Capability absorbed | Hub appendix |
|-----------------|---------------------|--------------|
| ``admin-service` (support module)` | support tickets, conversations, escalations — as a separately permissioned module (`support.admin` scope) | [3.38](#338-support) |

> `admin-service` keeps the `SUPER_ADMIN` permission preset. The
> preset membership is **1 × `platform.super_admin` + 20 ×
> `<service>.admin` scopes**.

### 2.14 ledger-service

No removed services are absorbed into `ledger-service`. It remains
the platform's authoritative double-entry ledger.

### 2.15 Unchanged survivors

The following 5 services are unchanged in this consolidation:
`identity-service`, `file-service`, `audit-service`, `api-gateway`,
`search-service`, `fraud-risk-service`.

## 3. Per-service migration record

Each subsection below preserves the unique schemas, events,
endpoints, and operational notes from one removed service. The
absorbing service's "Removed predecessor capability" appendix
mirrors and links back here.

> **Compatibility window note.** For at least six calendar months
> from 2026-08-05, every event topic, REST path, and database
> schema listed below is **also** published under the removed-
> service name by the absorbing service. Old topic names are not
> renamed; old REST paths are 301-redirected to the canonical path
> under the absorbing service; old schema names are mirrored as
> readable views in the absorbing service's schema.

### 3.1 user-profile

**Absorbed by:** `customer-service`.

**Bounded context:** cross-persona user data (display name, avatar,
locale, notification preferences, device list).

**Schema rename:** `user_profile` → `customer` (separate namespace
under the `customer` schema: `customer.user_profiles`).

**Tables absorbed:**

- `user_profile.user_profiles` → `customer.user_profiles`.
- `user_profile.devices` → `customer.devices`.
- `user_profile.notification_preferences` →
  `customer.notification_preferences`.

**REST endpoints (now on `customer-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET | `/v1/users/{id}/profile` | bearer (self) | read profile |
| PATCH | `/v1/users/{id}/profile` | bearer (self) | update profile |
| GET | `/v1/users/{id}/devices` | bearer (self) | list devices |
| DELETE | `/v1/users/{id}/devices/{device_id}` | bearer (self) | remove device |
| GET | `/v1/users/{id}/notification-preferences` | bearer (self) | read prefs |
| PATCH | `/v1/users/{id}/notification-preferences` | bearer (self) | update prefs |

**Events produced:** `user.profile.updated.v1`,
`user.device.registered.v1`, `user.device.removed.v1`,
`user.notification_preferences.updated.v1`.

**Events consumed:** `identity.user.created.v1`.

---

### 3.2 address

**Absorbed by:** `customer-service`.

**Bounded context:** saved addresses (ride pickup, food delivery),
geocoded + normalised, tagged.

**Schema rename:** `address` → `customer`.

**Tables absorbed:**

- `address.addresses` → `customer.addresses` (linked to a
  `user_id`; one user may have many).

**REST endpoints (now on `customer-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/users/{id}/addresses` | bearer (self) | create |
| GET  | `/v1/users/{id}/addresses` | bearer (self) | list |
| PATCH | `/v1/addresses/{address_id}` | bearer (self) | update |
| DELETE | `/v1/addresses/{address_id}` | bearer (self) | delete |

**Events produced:** `address.created.v1`, `address.updated.v1`,
`address.deleted.v1`.

**Events consumed:** (none — synchronous only).

---

### 3.3 driver-availability

**Absorbed by:** `driver-service`.

**Bounded context:** driver online state machine.

**Schema rename:** `driver_availability` → `driver`.

**Tables absorbed:**

- `driver_availability.online_state` → `driver.online_state`
  (state: `offline`, `online`, `busy`, `paused`).
- `driver_availability.shifts` → `driver.shifts`.
- `driver_availability.accepted_ride_types` →
  `driver.accepted_ride_types`.

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/drivers/{id}/online` | bearer (driver) | go online |
| POST | `/v1/drivers/{id}/offline` | bearer (driver) | go offline |
| POST | `/v1/drivers/{id}/shift` | bearer (driver) | open / close shift |
| POST | `/v1/drivers/{id}/accepted-types` | bearer (driver) | set accepted ride types |
| GET | `/v1/drivers/{id}/availability` | bearer | read current availability |

**Events produced:** `driver.availability.online.v1`,
`driver.availability.offline.v1`, `driver.availability.busy.v1`,
`driver.availability.zone.changed.v1`.

**Events consumed:** `driver.approved.v1`, `driver.suspended.v1`,
`dispatch.matched.v1`, `trip.completed.v1`, `trip.cancelled.v1`.

**Validation:** refuse offline if `busy`.

---

### 3.4 driver-location

**Absorbed by:** `driver-service`.

**Bounded context:** high-frequency driver location stream.

**Schema rename:** `driver_location` → `driver`.

**Tables absorbed:**

- `driver_location.current_location` → `driver.current_location`.
- `driver_location.locations` → `driver.location_trail`
  (RANGE on `recorded_at`, monthly; pre-create 12 months).

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/drivers/{id}/locations` | bearer (driver) | ingest GPS ping |
| GET  | `/v1/drivers/{id}/location` | bearer | last-known location |
| GET  | `/v1/drivers/{id}/locations/recent?minutes=N` | bearer | recent trail |

**Events produced:** `driver.location.updated.v1` (curated 1 Hz).

**Events consumed:** `driver.availability.online.v1`,
`driver.availability.offline.v1`.

**Stale rule:** no ping in 60 s → `is_stale = true`.

---

### 3.5 dispatch

**Absorbed by:** `driver-service`.

**Bounded context:** ride matching + assignment ledger + offer /
accept / expire flow + fairness.

**Schema rename:** `dispatch` → `driver`.

**Tables absorbed:**

- `dispatch.match_attempts` → `driver.match_attempts`
  (RANGE on `started_at`, monthly; 3-year retention).
- `dispatch.match_offers` → `driver.match_offers` (append-only).
- `dispatch.city_config` → `driver.dispatch_city_config`.

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/match` | bearer (service) | start a match |
| GET  | `/v1/match/{id}` | bearer | read a match attempt |
| POST | `/v1/match/{id}/accept` | bearer (driver) | driver accepts |
| POST | `/v1/match/{id}/reject` | bearer (driver) | driver rejects |
| POST | `/v1/match/{id}/cancel` | bearer (service / admin) | cancel |
| POST | `/v1/match/{id}/reassign` | bearer (admin) | force reassignment |
| GET  | `/v1/match/metrics` | bearer (admin) | operational counters |

**Events produced:** `dispatch.matched.v1`, `dispatch.no_driver.v1`,
`dispatch.offer.expired.v1`.

**Events consumed:** `ride.request.created.v1` (from `trip-service`),
`driver.location.updated.v1`, `driver.availability.online.v1`,
`driver.availability.busy.v1`.

**Fairness:** fairness score = `offers_last_hour` ascending then
`rejections_last_hour` ascending, then ETA ascending.

---

### 3.6 driver-incentive

**Absorbed by:** `driver-service`.

**Bounded context:** driver incentives — quests, bonuses, surge
guarantees, eligibility. (Surge **pricing** stays in
`pricing-service`; this capability only consumes the resulting
surge value.)

**Schema rename:** `driver_incentive` → `driver`.

**Tables absorbed:**

- `driver_incentive.quests` → `driver.quests`.
- `driver_incentive.bonuses` → `driver.bonuses`.
- `driver_incentive.guarantees` → `driver.guarantees`.
- `driver_incentive.eligibility_rules` →
  `driver.incentive_eligibility`.
- `driver_incentive.accruals` → `driver.incentive_accruals`
  (RANGE on `accrued_at`, monthly).

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/incentives/quests` | bearer (admin) | create a quest |
| POST | `/v1/incentives/bonuses` | bearer (admin) | create a bonus |
| POST | `/v1/incentives/guarantees` | bearer (admin) | create a guarantee |
| GET  | `/v1/drivers/{id}/incentives` | bearer | list eligible / earned |
| GET  | `/v1/incentives/metrics` | bearer (admin) | operational counters |

**Events produced:** `driver.incentive.earned.v1`.

**Events consumed:** `trip.completed.v1`.

---

### 3.7 vehicle

**Absorbed by:** `driver-service`.

**Bounded context:** vehicles owned by drivers / couriers;
registration, insurance, inspection.

**Schema rename:** `vehicle` → `driver` (vehicles are linked to a
`driver_id` here; for courier-owned vehicles the cross-service
UUID `courier_id` reference is also stored).

**Tables absorbed:**

- `vehicle.vehicles` → `driver.vehicles`.
- `vehicle.insurance` → `driver.vehicle_insurance`.
- `vehicle.inspections` → `driver.vehicle_inspections`.

**REST endpoints (now on `driver-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/drivers/{id}/vehicles` | bearer (driver) | register a vehicle |
| GET  | `/v1/drivers/{id}/vehicles` | bearer (driver) | list vehicles |
| PATCH | `/v1/vehicles/{vehicle_id}` | bearer (driver) | update |
| POST | `/v1/vehicles/{vehicle_id}/insurance` | bearer (driver) | upload insurance |
| POST | `/v1/vehicles/{vehicle_id}/inspections` | bearer (driver) | upload inspection |

**Events produced:** `vehicle.registered.v1`,
`vehicle.approved.v1`, `vehicle.insurance.expired.v1`,
`vehicle.inspection.expired.v1`.

**Events consumed:** (none — synchronous only).

---

### 3.8 ride-request

**Absorbed by:** `trip-service`.

**Bounded context:** ride booking aggregate (requested, matched,
cancelled, expired).

**Schema rename:** `ride_request` → `trip`.

**Tables absorbed:**

- `ride_request.ride_requests` → `trip.ride_requests`.

**REST endpoints (now on `trip-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/rides` | bearer (customer) | create ride request |
| GET  | `/v1/rides/{id}` | bearer | read |
| POST | `/v1/rides/{id}/cancel` | bearer (customer) | cancel |

**Events produced:** `ride.request.created.v1`,
`ride.request.matched.v1`, `ride.request.cancelled.v1`,
`ride.request.expired.v1`.

**Events consumed:** `customer.created.v1`,
`dispatch.matched.v1` (from `driver-service`).

---

### 3.9 scheduled-ride

**Absorbed by:** `trip-service`.

**Bounded context:** scheduled (future-dated) ride jobs;
materialisation into live requests.

**Schema rename:** `scheduled_ride` → `trip`.

**Tables absorbed:**

- `scheduled_ride.scheduled_rides` → `trip.scheduled_rides`.

**REST endpoints (now on `trip-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/rides/scheduled` | bearer (customer) | schedule |
| GET  | `/v1/rides/scheduled/{id}` | bearer | read |
| DELETE | `/v1/rides/scheduled/{id}` | bearer (customer) | cancel |

**Events produced:** `scheduled_ride.due.v1`.

**Events consumed:** (none).

---

### 3.10 ride-safety

**Absorbed by:** `trip-service`.

**Bounded context:** trip safety state and emergency response (SOS,
share-trip, audio recording, incident reports).

**Schema rename:** `ride_safety` → `trip`.

**Tables absorbed:**

- `ride_safety.safety_state` → `trip.safety_state`.
- `ride_safety.incidents` → `trip.incidents`.
- `ride_safety.share_links` → `trip.share_links`.

**REST endpoints (now on `trip-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/trips/{id}/sos` | bearer (rider / driver) | SOS |
| POST | `/v1/trips/{id}/share` | bearer (rider) | create share link |
| POST | `/v1/trips/{id}/incident` | bearer (driver) | report incident |
| GET  | `/v1/trips/{id}/safety` | bearer | read safety state |

**Events produced:** `ride.safety.sos.v1`,
`ride.safety.share.v1`, `ride.safety.incident.v1`.

**Events consumed:** `trip.started.v1` (own producer).

---

### 3.11 ride-history

**Absorbed by:** `trip-service`.

**Bounded context:** denormalised read model of trips, payments,
reviews.

**Schema rename:** `ride_history` → `trip`.

**Tables absorbed:**

- `ride_history.trip_views` → `trip.history_views` (read-only,
  replica-allowed).

**REST endpoints (now on `trip-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/customers/{id}/trips` | bearer (customer) | list |
| GET  | `/v1/drivers/{id}/trips` | bearer (driver) | list |
| GET  | `/v1/trips/{id}/summary` | bearer | trip summary |

**Events consumed:** `trip.completed.v1` (own producer),
`ride.payment.completed.v1` (from `payment-service`),
`review.submitted.v1`.

---

### 3.12 review-rating

**Split across:** `trip-service` (trip reviews), `food-order-service`
(food reviews), `search-service` (search reviews).

**Schema rename:** `review` → split into
`trip.reviews`, `food_order.reviews`, `search.reviews`.

**Tables absorbed:**

- `review.reviews` (filtered by `subject_kind`) →
  `trip.reviews` (where `subject_kind = 'trip'`),
  `food_order.reviews` (where `subject_kind = 'food_order'`),
  `search.reviews` (where `subject_kind = 'restaurant'` /
  `'menu_item'`).
- `review.aggregates` → split across `trip.rating_aggregates`,
  `food_order.rating_aggregates`, `search.rating_aggregates`.

**REST endpoints (split):**

| Method | URI | Service |
|--------|-----|---------|
| POST | `/v1/trips/{id}/review` | `trip-service` |
| GET  | `/v1/trips/{id}/reviews` | `trip-service` |
| POST | `/v1/orders/{id}/review` | `food-order-service` |
| GET  | `/v1/restaurants/{id}/reviews` | `food-order-service` |
| GET  | `/v1/search/reviews` | `search-service` |

**Events produced (split):**

- `review.submitted.v1` (still emitted by all three absorbing
  services for the compatibility window; old topic preserved).
- `review.aggregated.v1` (emitted by each absorbing service for
  its slice).
- New: `trip.review.read.v1`, `food.review.read.v1`.

**Events consumed:** (none — synchronous only).

---

### 3.13 tax

**Absorbed by:** `pricing-service`.

**Bounded context:** tax jurisdiction rules, product tax codes,
exemptions (read-mostly).

**Schema rename:** `tax` → `pricing`.

**Tables absorbed:**

- `tax.jurisdiction_rules` → `pricing.tax_jurisdiction_rules`.
- `tax.product_tax_codes` → `pricing.product_tax_codes`.
- `tax.exemptions` → `pricing.tax_exemptions`.

**REST endpoints (now on `pricing-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/tax/calculate` | bearer (service) | compute tax |
| GET  | `/v1/tax/rules?jurisdiction=…` | bearer (admin) | read rules |
| POST | `/v1/tax/rules` | bearer (admin) | upsert rule |

**Events produced:** `tax.calculated.v1`.

**Events consumed:** `configuration.updated.v1`.

---

### 3.14 promotion

**Absorbed by:** `pricing-service`.

**Bounded context:** coupons, campaigns, redemption rules,
redemption history.

**Schema rename:** `promotion` → `pricing`.

**Tables absorbed:**

- `promotion.coupons` → `pricing.coupons`.
- `promotion.campaigns` → `pricing.campaigns`.
- `promotion.redemption_rules` → `pricing.redemption_rules`.
- `promotion.redemptions` → `pricing.redemptions`.

**REST endpoints (now on `pricing-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/promotions/coupons` | bearer (admin) | create |
| GET  | `/v1/promotions/coupons/{code}` | bearer (customer) | read |
| POST | `/v1/promotions/redeem` | bearer (customer) | redeem |
| GET  | `/v1/promotions/metrics` | bearer (admin) | metrics |

**Events produced:** `promotion.redeemed.v1`,
`promotion.created.v1`, `promotion.disabled.v1`.

**Events consumed:** `customer.segment.changed.v1`.

---

### 3.15 loyalty-rules

**Absorbed by:** `pricing-service` (rules only).

> The **loyalty account** (per-user balance, history) is owned by
> `customer-service`. See 2.1.

**Schema rename:** `loyalty` (rules namespace) → `pricing`.

**Tables absorbed:**

- `loyalty.tiers` → `pricing.loyalty_tiers`.
- `loyalty.earn_rules` → `pricing.loyalty_earn_rules`.
- `loyalty.burn_rules` → `pricing.loyalty_burn_rules`.
- `loyalty.promo_bindings` → `pricing.loyalty_promo_bindings`.

**REST endpoints (now on `pricing-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/loyalty/tiers` | bearer | read tiers |
| POST | `/v1/loyalty/tiers` | bearer (admin) | upsert tier |
| GET  | `/v1/loyalty/earn-rules` | bearer | read earn rules |
| POST | `/v1/loyalty/earn-rules` | bearer (admin) | upsert |

**Events produced:** `loyalty.tier.changed.v1`.

**Events consumed:** `customer.loyalty_account.changed.v1`
(from `customer-service`).

---

### 3.16 merchant

**Absorbed by:** `restaurant-service`.

**Bounded context:** merchant (legal entity).

**Schema rename:** `merchant` → `restaurant`.

**Tables absorbed:**

- `merchant.merchants` → `restaurant.merchants`.

**REST endpoints (now on `restaurant-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/merchants` | bearer (admin) | create |
| GET  | `/v1/merchants/{id}` | bearer | read |
| PATCH | `/v1/merchants/{id}` | bearer (admin) | update |
| POST | `/v1/merchants/{id}/approve` | bearer (admin) | approve |
| POST | `/v1/merchants/{id}/suspend` | bearer (admin) | suspend |

**Events produced:** `merchant.created.v1`,
`merchant.approved.v1`, `merchant.suspended.v1`,
`merchant.updated.v1`.

**Events consumed:** `identity.user.created.v1`.

---

### 3.17 branch

**Absorbed by:** `restaurant-service`.

**Bounded context:** branches (physical locations, hours, prep
capacity).

**Schema rename:** `branch` → `restaurant`.

**Tables absorbed:**

- `branch.branches` → `restaurant.branches`.
- `branch.hours` → `restaurant.branch_hours`.

**REST endpoints (now on `restaurant-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/restaurants/{id}/branches` | bearer (manager) | create branch |
| GET  | `/v1/restaurants/{id}/branches` | bearer | list |
| PATCH | `/v1/branches/{id}` | bearer (manager) | update |
| POST | `/v1/branches/{id}/hours` | bearer (manager) | set hours |

**Events produced:** `branch.created.v1`, `branch.updated.v1`,
`branch.hours.changed.v1`, `branch.busy.v1`.

**Events consumed:** `restaurant.created.v1` (own producer),
`zone.updated.v1` (from `geolocation-service`).

---

### 3.18 menu

**Absorbed by:** `restaurant-service`.

**Bounded context:** categories, products, modifiers, add-ons,
pricing.

**Schema rename:** `menu` → `restaurant`.

**Tables absorbed:**

- `menu.categories` → `restaurant.menu_categories`.
- `menu.products` → `restaurant.menu_products`.
- `menu.modifiers` → `restaurant.menu_modifiers`.
- `menu.add_ons` → `restaurant.menu_add_ons`.

**REST endpoints (now on `restaurant-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/restaurants/{id}/menu/categories` | bearer (manager) | create category |
| POST | `/v1/restaurants/{id}/menu/products` | bearer (manager) | create product |
| GET  | `/v1/restaurants/{id}/menu` | bearer | read menu |
| PATCH | `/v1/menu/products/{product_id}/price` | bearer (manager) | change price |
| POST | `/v1/menu/products/{product_id}/86` | bearer (manager) | mark unavailable |

**Events produced:** `menu.created.v1`, `menu.updated.v1`,
`menu.item.price.changed.v1`, `menu.item.unavailable.v1`.

**Events consumed:** `restaurant.created.v1` (own producer),
`inventory.item.86d.v1` (from absorbed inventory capability).

---

### 3.19 inventory

**Absorbed by:** `restaurant-service`.

**Bounded context:** stock counts, time-bound availability,
86-list.

**Schema rename:** `inventory` → `restaurant`.

**Tables absorbed:**

- `inventory.stock_counts` → `restaurant.stock_counts`.
- `inventory.availability` → `restaurant.stock_availability`.
- `inventory.eighty_six` → `restaurant.eighty_six`.

**REST endpoints (now on `restaurant-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/menu/products/{product_id}/stock` | bearer (manager) | upsert |
| POST | `/v1/menu/products/{product_id}/restock` | bearer (manager) | restock |

**Events produced:** `inventory.item.out_of_stock.v1`,
`inventory.item.restocked.v1`, `inventory.item.86d.v1`.

**Events consumed:** `menu.item.unavailable.v1` (own producer).

---

### 3.20 restaurant-staff

**Absorbed by:** `restaurant-service`.

**Schema rename:** `restaurant_staff` → `restaurant`.

**Tables absorbed:**

- `restaurant_staff.staff` → `restaurant.staff`.
- `restaurant_staff.invitations` → `restaurant.staff_invitations`.
- `restaurant_staff.roles` → `restaurant.staff_roles`.
- `restaurant_staff.devices` → `restaurant.staff_devices`.

**REST endpoints (now on `restaurant-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/restaurants/{id}/staff/invite` | bearer (manager) | invite |
| POST | `/v1/staff/activate` | bearer (invitee) | activate |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/roles` | bearer (manager) | assign roles |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/devices` | bearer (staff) | register device |
| POST | `/v1/restaurants/{id}/staff/{staff_id}/deactivate` | bearer (manager) | deactivate |

**Events produced:** `staff.invited.v1`, `staff.activated.v1`,
`staff.deactivated.v1`.

**Events consumed:** `restaurant.created.v1` (own producer).

---

### 3.21 cart

**Absorbed by:** `food-order-service`.

**Bounded context:** shopping cart aggregate.

**Schema rename:** `cart` → `food_order`.

**Tables absorbed:**

- `cart.carts` → `food_order.carts`.
- `cart.cart_items` → `food_order.cart_items`.

**REST endpoints (now on `food-order-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/carts` | bearer (customer) | create |
| GET  | `/v1/carts/{id}` | bearer | read |
| POST | `/v1/carts/{id}/items` | bearer (customer) | add item |
| DELETE | `/v1/carts/{id}/items/{item_id}` | bearer (customer) | remove item |
| POST | `/v1/carts/{id}/checkout` | bearer (customer) | start checkout |

**Events produced:** `cart.created.v1`, `cart.updated.v1`,
`cart.checked_out.v1`, `cart.abandoned.v1`.

**Events consumed:** `menu.item.price.changed.v1` (own producer),
`menu.item.unavailable.v1` (own producer),
`restaurant.offline.v1` (own producer).

---

### 3.22 checkout

**Absorbed by:** `food-order-service`.

**Bounded context:** checkout session (address, slot, payment
method, final quote).

**Schema rename:** `checkout` → `food_order`.

**Tables absorbed:**

- `checkout.sessions` → `food_order.checkout_sessions`.

**REST endpoints (now on `food-order-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/checkout` | bearer (customer) | create session |
| GET  | `/v1/checkout/{id}` | bearer | read |
| POST | `/v1/checkout/{id}/complete` | bearer (customer) | complete |
| POST | `/v1/checkout/{id}/fail` | bearer (customer) | fail |

**Events produced:** `checkout.completed.v1`, `checkout.failed.v1`.

**Events consumed:** `cart.updated.v1` (own producer),
`pricing.quote.created.v1` (from `pricing-service`).

---

### 3.23 restaurant-order-mgmt

**Absorbed by:** `food-order-service`.

**Bounded context:** restaurant-side queue, accept/reject timer,
prep state.

**Schema rename:** `restaurant_order_mgmt` → `food_order`.

**Tables absorbed:**

- `restaurant_order_mgmt.queue` → `food_order.queue`.
- `restaurant_order_mgmt.timers` → `food_order.queue_timers`.
- `restaurant_order_mgmt.rejections` → `food_order.queue_rejections`.

**REST endpoints (now on `food-order-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/orders/{id}/accept` | bearer (operator) | accept |
| POST | `/v1/orders/{id}/reject` | bearer (operator) | reject |
| POST | `/v1/orders/{id}/preparing` | bearer (operator) | mark preparing |
| POST | `/v1/orders/{id}/ready` | bearer (operator) | mark ready |
| GET  | `/v1/queue?branch_id=…` | bearer (operator) | read queue |

**Events produced:** `food.order.accepted.v1`,
`food.order.rejected.v1`, `food.order.preparing.v1`,
`food.order.ready.v1`.

**Events consumed:** `food.order.placed.v1` (own producer).

---

### 3.24 courier-dispatch

**Absorbed by:** `courier-service`.

**Bounded context:** courier matching, assignment ledger, batched
offers, no-courier handling.

**Schema rename:** `courier_dispatch` → `courier`.

**Tables absorbed:**

- `courier_dispatch.dispatches` → `courier.dispatches`.
- `courier_dispatch.assignments` → `courier.assignments`
  (RANGE on `assigned_at`, monthly; 3-year retention; append-only).
- `courier_dispatch.courier_pool_entries` → `courier.courier_pool_entries`.
- `courier_dispatch.city_config` → `courier.city_config`.
- `courier_dispatch.outbox` → `courier.outbox`.
- `courier_dispatch.inbox` → `courier.inbox`.

**REST endpoints (now on `courier-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/dispatches` | bearer (service) | start dispatch |
| GET  | `/v1/dispatches/{id}` | bearer | read |
| POST | `/v1/dispatches/{id}/offers` | bearer (internal) | record offer |
| POST | `/v1/dispatches/{id}/accept` | bearer (courier) | accept |
| POST | `/v1/dispatches/{id}/reject` | bearer (courier) | reject |
| POST | `/v1/dispatches/{id}/cancel` | bearer (service / admin) | cancel |
| POST | `/v1/dispatches/{id}/reassign` | bearer (service / admin) | reassign |
| GET  | `/v1/dispatches/metrics` | bearer (admin) | metrics |

**Events produced:** `delivery.courier.assigned.v1`,
`delivery.dispatch.no_courier.v1`,
`delivery.dispatch.offer.expired.v1`,
`delivery.dispatch.reassigned.v1`.

**Events consumed:** `food.order.ready.v1` (own producer).

---

### 3.25 courier-tracking

**Absorbed by:** `courier-service`.

**Bounded context:** high-frequency courier location stream.

**Schema rename:** `courier_tracking` → `courier`.

**Tables absorbed:**

- `courier_tracking.current_location` → `courier.current_location`.
- `courier_tracking.locations` → `courier.location_trail`
  (RANGE on `recorded_at`, monthly).

**REST endpoints (now on `courier-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/couriers/{id}/locations` | bearer (courier) | ingest GPS |
| GET  | `/v1/couriers/{id}/location` | bearer | last-known |
| GET  | `/v1/couriers/{id}/locations/recent?minutes=N` | bearer | trail |

**Events produced:** `courier.location.updated.v1`.

**Events consumed:** `courier.availability.online.v1`,
`courier.availability.offline.v1`.

---

### 3.26 delivery

**Absorbed by:** `courier-service`.

**Bounded context:** delivery aggregate (assigned → en_route_pickup
→ arrived_pickup → picked_up → en_route_dropoff → delivered /
failed).

**Schema rename:** `delivery` → `courier`.

**Tables absorbed:**

- `delivery.deliveries` → `courier.deliveries`.

**REST endpoints (now on `courier-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/deliveries/{id}` | bearer | read |
| POST | `/v1/deliveries/{id}/arrive-pickup` | bearer (courier) | arrived pickup |
| POST | `/v1/deliveries/{id}/pickup` | bearer (courier) | picked up |
| POST | `/v1/deliveries/{id}/in-transit` | bearer (courier) | in transit |
| POST | `/v1/deliveries/{id}/complete` | bearer (courier) | complete |
| POST | `/v1/deliveries/{id}/fail` | bearer (courier / admin) | fail |

**Events produced:** `delivery.pickup.v1`,
`delivery.in_transit.v1`, `delivery.completed.v1`,
`delivery.failed.v1`.

**Events consumed:** `delivery.courier.assigned.v1` (own producer),
`courier.location.updated.v1` (own producer).

---

### 3.27 ride-payment-integration

**Absorbed by:** `payment-service`.

**Bounded context:** ride payment saga orchestration.

**Schema rename:** `ride_payment_integration` → `payment`.

**Tables absorbed:**

- `ride_payment_integration.sagas` → `payment.ride_sagas`.
- `ride_payment_integration.idempotency_keys` → `payment.ride_idempotency`.

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/ride-payment/sagas/{trip_id}` | bearer (admin / support) | read |
| POST | `/v1/ride-payment/sagas/{trip_id}/retry` | bearer (admin) | retry |
| POST | `/v1/ride-payment/sagas/{trip_id}/compensate` | bearer (admin) | compensate |

**Events produced:** `ride.payment.completed.v1`,
`ride.payment.failed.v1`.

**Events consumed:** `trip.completed.v1` (from `trip-service`).

---

### 3.28 food-payment-integration

**Absorbed by:** `payment-service`.

**Bounded context:** food payment saga orchestration.

**Schema rename:** `food_payment_integration` → `payment`.

**Tables absorbed:**

- `food_payment_integration.sagas` → `payment.food_sagas`.
- `food_payment_integration.idempotency_keys` → `payment.food_idempotency`.

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/food-payment/sagas/{food_order_id}` | bearer (admin / support) | read |
| POST | `/v1/food-payment/sagas/{food_order_id}/retry` | bearer (admin) | retry |
| POST | `/v1/food-payment/sagas/{food_order_id}/compensate` | bearer (admin) | compensate |

**Events produced:** `food.payment.completed.v1`,
`food.payment.failed.v1`, `merchant.settlement.created.v1`.

**Events consumed:** `delivery.completed.v1` (from `courier-service`),
`payment.captured.v1` (own producer).

---

### 3.29 wallet

**Absorbed by:** `payment-service`.

**Bounded context:** customer wallet balance, holds, top-ups.

**Schema rename:** `wallet` → `payment`.

**Tables absorbed:**

- `wallet.balances` → `payment.wallet_balances`.
- `wallet.holds` → `payment.wallet_holds`.
- `wallet.topups` → `payment.wallet_topups`.
- `wallet.ledger_entries` → `payment.wallet_entries` (RANGE on
  `created_at`, monthly).

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/wallets/me` | bearer (customer) | read balance |
| GET  | `/v1/wallets/me/statement` | bearer (customer) | statement |
| POST | `/v1/wallets/me/topup` | bearer (customer) | top up |
| POST | `/v1/wallets/{id}/holds` | bearer (service) | place a hold |
| POST | `/v1/wallets/{id}/holds/{hold_id}/capture` | bearer (service) | capture |
| POST | `/v1/wallets/{id}/holds/{hold_id}/release` | bearer (service) | release |

**Events produced:** `wallet.credited.v1`, `wallet.debited.v1`,
`wallet.held.v1`, `wallet.released.v1`.

**Events consumed:** `payment.captured.v1` (own producer),
`payment.refund.completed.v1` (own producer),
`trip.reward.granted.v1` (when `trip.reward.user.kind =
wallet_credit`; from `trip-service`).

---

### 3.30 driver-earnings

**Absorbed by:** `payment-service`.

**Bounded context:** driver earnings ledger, withdrawals.

**Schema rename:** `driver_earnings` → `payment`.

**Tables absorbed:**

- `driver_earnings.earnings` → `payment.driver_earnings`
  (RANGE on `accrued_at`, monthly).
- `driver_earnings.balances` → `payment.driver_balances`.
- `driver_earnings.withdrawals` → `payment.driver_withdrawals`.
- `driver_earnings.bank_details` → `payment.driver_bank_details`.

**Events produced:** `driver.earning.accrued.v1`,
`driver.withdrawal.requested.v1`,
`driver.withdrawal.completed.v1`.

**Events consumed:** `ride.payment.completed.v1` (own producer),
`trip.completed.v1` (from `trip-service`),
`trip.reward.granted.v1` (guaranteed top-up),
`trip.reward.reversed.v1` (reverse),
`driver.incentive.earned.v1` (from `driver-service`).

---

### 3.31 courier-earnings

**Absorbed by:** `payment-service`.

**Bounded context:** courier earnings ledger, withdrawals.

**Schema rename:** `courier_earnings` → `payment`.

**Tables absorbed:**

- `courier_earnings.earnings` → `payment.courier_earnings`
  (RANGE on `accrued_at`, monthly).
- `courier_earnings.balances` → `payment.courier_balances`.
- `courier_earnings.withdrawals` → `payment.courier_withdrawals`.
- `courier_earnings.bank_details` → `payment.courier_bank_details`.

**Events produced:** `courier.earning.accrued.v1`,
`courier.withdrawal.requested.v1`,
`courier.withdrawal.completed.v1`.

**Events consumed:** `delivery.completed.v1` (own producer),
`food.payment.completed.v1` (own producer),
`courier.incentive.earned.v1` (from `courier-service`).

---

### 3.32 restaurant-settlement

**Absorbed by:** `payment-service`.

**Bounded context:** merchant payable, payout schedule, payout
runs, disputes, **COD payment state**.

**Schema rename:** `restaurant_settlement` → `payment`.

**Tables absorbed:**

- `restaurant_settlement.payables` → `payment.merchant_payables`.
- `restaurant_settlement.payouts` → `payment.merchant_payouts`
  (RANGE on `scheduled_for`, monthly; pre-create 3 months).
- `restaurant_settlement.disputes` → `payment.merchant_disputes`.
- `restaurant_settlement.commissions` → `payment.merchant_commissions`.
- `restaurant_settlement.cod_state` → `payment.cod_state`.

**REST endpoints (now on `payment-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/merchants/{id}/payable` | bearer (merchant) | read payable |
| GET  | `/v1/merchants/{id}/statement` | bearer (merchant) | statement |
| POST | `/v1/merchants/{id}/payouts/schedule` | bearer (admin) | schedule |
| POST | `/v1/merchants/{id}/disputes` | bearer (admin) | open dispute |
| POST | `/v1/orders/{id}/cod/mark-collected` | bearer (courier) | mark COD collected |

**Events produced:** `merchant.settlement.accrued.v1`,
`merchant.payout.scheduled.v1`,
`merchant.payout.completed.v1`,
`merchant.dispute.opened.v1`,
`merchant.dispute.resolved.v1`,
`payment.cod.collected.v1`.

**Events consumed:** `food.payment.completed.v1` (own producer),
`merchant.suspended.v1` (from `restaurant-service`).

---

### 3.33 eta-routing

**Absorbed by:** `geolocation-service`.

**Bounded context:** stateless adapter over the map provider; ETAs,
route polylines, distance, alternatives.

**Schema rename:** `eta_routing` → `geolocation`.

**Tables absorbed:**

- `eta_routing.cache` → `geolocation.eta_cache` (TTL cache).
- `eta_routing.routes` → `geolocation.routes` (TTL cache).

**REST endpoints (now on `geolocation-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/eta` | bearer (service) | compute ETA |
| POST | `/v1/route` | bearer (service) | compute route |
| POST | `/v1/route/alternatives` | bearer (service) | alternatives |

**Events produced:** `eta.computed.v1`, `route.computed.v1`.

**Events consumed:** (none).

---

### 3.34 zone

**Absorbed by:** `geolocation-service`.

**Bounded context:** cities, service zones, surge zones,
restricted zones, zone hours.

**Schema rename:** `zone` → `geolocation`.

**Tables absorbed:**

- `zone.cities` → `geolocation.cities`.
- `zone.service_zones` → `geolocation.service_zones`.
- `zone.surge_zones` → `geolocation.surge_zones`.
- `zone.restricted_zones` → `geolocation.restricted_zones`.
- `zone.zone_hours` → `geolocation.zone_hours`.

**REST endpoints (now on `geolocation-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/zones?city_id=…` | bearer | list zones |
| GET  | `/v1/zones/{id}` | bearer | read zone |
| POST | `/v1/zones/{id}/surge` | bearer (admin) | set surge |
| POST | `/v1/zones/{id}/restrict` | bearer (admin) | restrict |

**Events produced:** `zone.updated.v1`, `zone.surge.updated.v1`.

**Events consumed:** (none).

---

### 3.35 communication-gateway

**Absorbed by:** `notification-service`.

**Bounded context:** provider anti-corruption layer in front of
external messaging providers (SMS / email / push / WhatsApp);
plug-in provider model.

> The **immutable notification template-version snapshot chain**
> (`notification.template_version_snapshot`) remains append-only
> and is owned by `notification-service`. The absorbed provider
> layer is re-mounted inside this service and continues to call
> the same providers with the same
> `template_version_snapshot_id` value.

**Schema rename:** `comms_gateway` → `notification`.

**Tables absorbed:**

- `comms_gateway.providers` → `notification.providers`.
- `comms_gateway.send_logs` → `notification.send_logs`.
- `comms_gateway.capability_matrix` →
  `notification.capability_matrix`.

**REST endpoints (now on `notification-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/notify/sms` | bearer (service) | send SMS |
| POST | `/v1/notify/email` | bearer (service) | send email |
| POST | `/v1/notify/push` | bearer (service) | send push |
| POST | `/v1/notify/whatsapp` | bearer (service) | send WhatsApp |
| POST | `/v1/notify/providers/{id}/activate` | bearer (admin) | activate provider |
| POST | `/v1/notify/providers/{id}/disable` | bearer (admin) | disable provider |

**Events produced:** `comms.sms.sent.v1`, `comms.email.sent.v1`,
`comms.push.sent.v1`, `comms.whatsapp.accepted.v1`,
`comms.whatsapp.delivered.v1`, `comms.whatsapp.read.v1`,
`comms.whatsapp.failed.v1`,
`comms.whatsapp.template_status_update.v1`.

**Events consumed:** `notification.retry_requested.v1` (planned;
own producer).

---

### 3.36 feature-flag

**Absorbed by:** `configuration-service`.

**Bounded context:** flag definitions, overrides, rollout
percentages.

**Schema rename:** `feature_flag` → `configuration`.

**Tables absorbed:**

- `feature_flag.flags` → `configuration.flags`.
- `feature_flag.overrides` → `configuration.flag_overrides`.

**REST endpoints (now on `configuration-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/flags` | bearer | list |
| GET  | `/v1/flags/{name}` | bearer | read |
| POST | `/v1/flags` | bearer (admin) | create |
| POST | `/v1/flags/{name}/override` | bearer (admin) | override |

**Events produced:** `feature_flag.updated.v1`.

**Events consumed:** (none).

---

### 3.37 analytics

**Absorbed by:** `reporting-service`.

**Bounded context:** event ingestion to data warehouse; materialised
read models.

**Schema rename:** `analytics` → `reporting`.

**Tables absorbed:**

- `analytics.ingest_state` → `reporting.ingest_state`.
- `analytics.materialised_views` → `reporting.materialised_views`.

**REST endpoints (now on `reporting-service`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| GET  | `/v1/reports/{id}` | bearer (admin) | read report |
| POST | `/v1/reports/{id}/export` | bearer (admin) | export |
| GET  | `/v1/exports/{id}.csv` | bearer (admin) | download |

**Events consumed:** (every domain event).

---

### 3.38 support

**Absorbed by:** `admin-service` as a separately permissioned
module (scope: `support.admin`).

**Bounded context:** support tickets, conversations, attachments,
escalations.

**Schema rename:** `support` → `admin`.

**Tables absorbed:**

- `support.tickets` → `admin.support_tickets`.
- `support.conversations` → `admin.support_conversations`.
- `support.attachments` → `admin.support_attachments`.
- `support.escalations` → `admin.support_escalations`.

**REST endpoints (now on `admin-service`; require `support.admin`):**

| Method | URI | Auth | Purpose |
|--------|-----|------|---------|
| POST | `/v1/support/tickets` | bearer (admin) | open |
| GET  | `/v1/support/tickets/{id}` | bearer (admin) | read |
| POST | `/v1/support/tickets/{id}/messages` | bearer (admin) | post message |
| POST | `/v1/support/tickets/{id}/resolve` | bearer (admin) | resolve |

**Events produced:** `support.ticket.opened.v1`,
`support.ticket.resolved.v1`.

**Events consumed:** `payment.disputed.v1`,
`customer.suspended.v1`.

---

## 4. Cross-cutting compliance (preserved invariants)

- **46-gateway registry**: still owned by `payment-service`. File:
  [`services/payment-service/GATEWAYS.md`](services/payment-service/GATEWAYS.md).
- **Double-entry ledger**: still owned by `ledger-service`. Every
  payment, earning, payout, and wallet entry is a posting.
- **Accounting four-layer model**: layer 1 (customer wallet) inside
  `payment-service`; layer 2 (provider) inside `payment-service`;
  layer 3 (double-entry) inside `ledger-service`; layer 4
  (settlement) inside `payment-service`.
- **SUPER_ADMIN break-glass**: unchanged. `admin-service` keeps the
  role and the break-glass path; the preset membership is now
  **1 × `platform.super_admin` + 20 × `<service>.admin` scopes**
  (one per survivor).
- **Immutable notification snapshot chain**: `notification-service`
  continues to subscribe to every topic listed in this hub;
  producers change from removed services to survivors but topics
  and schemas are preserved for the compatibility window.
- **Partitioning conventions**: every RANGE-partitioned table
  listed in 3 carries the same `recorded_at` / `accrued_at` /
  `assigned_at` / `started_at` / `scheduled_for` / `created_at`
  monthly partition key with 12-month pre-creation (3-month for
  payout schedules); retention windows are unchanged.

## 5. Compatibility window

| Concern | Policy | Window |
|---------|--------|--------|
| Old event topics | same topic + same schema version, published by survivor | ≥ 6 months from 2026-08-05 |
| Old REST paths | 301 redirect to canonical survivor path | ≥ 6 months |
| Old schema names | readable view in survivor's schema | ≥ 6 months |
| Old metrics labels | preserved under removed-service label namespace | ≥ 6 months |

## 6. Validation checklist

- `MICROSERVICES_MAP.md` "Service Count Summary" reads **20**.
- `ADR_INDEX.md` includes ADR-0016 (Superseded) and ADR-0017
  (Accepted).
- Exactly **20 service directories** exist under `docs/services/`.
  The **38 removed directories do not exist** on disk.
- All 20 absorbing survivors (where applicable) carry a
  "Removed predecessor capability" appendix that mirrors the
  corresponding row in 2 above. (Five survivors — `identity`,
  `file`, `audit`, `api-gateway`, `search-service`,
  `fraud-risk-service` — carry no absorbed capabilities and no
  appendix.)
- `git grep` for any of the 38 removed service names returns hits
  only in this hub, in the absorbing service's "Removed predecessor
  capability" appendix, in the ADR-0016 / ADR-0017 narrative, and
  in narrative architecture context. No operational reference
  (config key, topic name, RBAC role, REST path, schema reference)
  points at a removed service as if it were still running.
- The platform-wide shared docs
  ([`shared/PLATFORM_BASELINE.md`](shared/PLATFORM_BASELINE.md),
  [`shared/CONVENTIONS.md`](shared/CONVENTIONS.md),
  [`shared/DEAL_FEATURE.md`](shared/DEAL_FEATURE.md),
  [`shared/LOOKUPS.md`](shared/LOOKUPS.md),
  [`shared/OSS_DEPENDENCIES.md`](shared/OSS_DEPENDENCIES.md),
  [`shared/INTEGRATION.md`](shared/INTEGRATION.md),
  [`shared/MODULES.md`](shared/MODULES.md)) reflect the
  20-service catalog and the survivor's responsibilities.
- Workflows ([`workflows/`](workflows/)) reference the survivor
  services only.
- Master plans and indexes reference 20 services only.

## 7. Related

- [ADR-0016 (Superseded)](architecture/adrs/0016-service-domain-consolidation.md).
- [ADR-0017: 20-Service Architecture](architecture/adrs/0017-20-service-architecture.md).
- [`architecture/MICROSERVICES_MAP.md`](architecture/MICROSERVICES_MAP.md).
- [`architecture/DATA_OWNERSHIP.md`](architecture/DATA_OWNERSHIP.md).
- [`architecture/EVENT_ARCHITECTURE.md`](architecture/EVENT_ARCHITECTURE.md).
- [`architecture/SERVICE_ISOLATION.md`](architecture/SERVICE_ISOLATION.md).
- [`architecture/DATABASE_ARCHITECTURE.md`](architecture/DATABASE_ARCHITECTURE.md).
- [`services/identity-service/`](services/identity-service/README.md).
- [`services/file-service/`](services/file-service/README.md).
- [`services/audit-service/`](services/audit-service/README.md).
- [`services/api-gateway/`](services/api-gateway/README.md).
- [`services/configuration-service/`](services/configuration-service/README.md).
- [`services/customer-service/`](services/customer-service/README.md).
- [`services/driver-service/`](services/driver-service/README.md).
- [`services/trip-service/`](services/trip-service/README.md).
- [`services/pricing-service/`](services/pricing-service/README.md).
- [`services/restaurant-service/`](services/restaurant-service/README.md).
- [`services/food-order-service/`](services/food-order-service/README.md).
- [`services/courier-service/`](services/courier-service/README.md).
- [`services/payment-service/`](services/payment-service/README.md).
- [`services/ledger-service/`](services/ledger-service/README.md).
- [`services/geolocation-service/`](services/geolocation-service/README.md).
- [`services/notification-service/`](services/notification-service/README.md).
- [`services/search-service/`](services/search-service/README.md).
- [`services/fraud-risk-service/`](services/fraud-risk-service/README.md).
- [`services/admin-service/`](services/admin-service/README.md).
- [`services/reporting-service/`](services/reporting-service/README.md).