# Service Integration Matrix

> **Purpose:** Complete integration dependency mapping for all 20 active microservices (38 consolidated per ADR-0017).
> **Updated:** 2026-08-05

## Quick Reference

| Service | Tier | Tech | Sync Deps | Async Consumes | Async Produces | Doc Link |
|---------|------|------|-----------|----------------|----------------|----------|
| configuration-service | 0 | Kotlin/Spring | None | customer.segment.changed, zone.surge.updated, feature_flag.updated | configuration.updated, configuration.rolled_back, feature_flag.updated | [Link](services/configuration-service/INTEGRATION.md) |
| api-gateway | 1 | Go/Envoy | identity, All services | identity.session.revoked, identity.user.suspended, configuration.updated | audit.api.request, gateway.rate_limit.exceeded | [Link](services/api-gateway/INTEGRATION.md) |
| audit-service | 1 | Go | None | All *.audit.* events + high-value events | audit.consumer.lag, audit.export.completed | [Link](services/audit-service/INTEGRATION.md) |
| identity-service | 1 | Node/TS | Keycloak | customer.created, driver.created, courier.created, configuration.updated | identity.user.created, identity.user.suspended, identity.session.revoked | [Link](services/identity-service/INTEGRATION.md) |
| ledger-service | 1 | Node/TS | None | payment.captured, wallet.credited, merchant.settlement.accrued, courier.earning.accrued, trip.reward.granted, trip.reward.reversed | ledger.posted, ledger.audit.reconciliation_drift | [Link](services/ledger-service/INTEGRATION.md) |
| geolocation-service | 1 | Go | Map Provider | None | geolocation.geocoded, geolocation.eta.computed, eta.computed, route.computed, zone.updated, zone.surge.updated | [Link](services/geolocation-service/INTEGRATION.md) |
| file-service | 1 | Go | S3, ClamAV | None | file.uploaded, file.scanned, file.deleted | [Link](services/file-service/INTEGRATION.md) |
| notification-service | 2 | Kotlin/Spring | (absorbed provider ACL — FCM, APNs, Twilio, AWS SES, WhatsApp) | trip.completed, food.order.placed, payment.failed, trip.reward.granted, trip.reward.reversed, comms.whatsapp.template_status_update | notification.sent, notification.failed, comms.sms.sent, comms.email.sent, comms.push.sent, comms.whatsapp.accepted | [Link](services/notification-service/INTEGRATION.md) |
| admin-service | 2 | Kotlin/Spring | All services | All service audit events, payment.disputed, customer.suspended | admin.action.performed, support.ticket.opened, support.ticket.resolved | [Link](services/admin-service/INTEGRATION.md) |
| fraud-risk-service | 2 | Python/FastAPI | identity | identity.session.created, payment.attempted | fraud.risk.scored, fraud.account.blocked | [Link](services/fraud-risk-service/INTEGRATION.md) |
| customer-service | 2 | Kotlin/Spring | identity, payment | identity.user.created, payment.method.saved, loyalty.tier.changed | customer.created, customer.updated, customer.suspended, address.*.v1, user.profile.*.v1, customer.loyalty_account.changed.v1 | [Link](services/customer-service/INTEGRATION.md) |
| driver-service | 2 | Kotlin/Spring | identity, payment, geolocation | vehicle.registered, document.expiring, customer.created, trip.completed, ride.request.created | driver.created, driver.approved, driver.suspended, vehicle.*.v1, driver.availability.*.v1, driver.location.updated.v1, dispatch.*.v1, driver.incentive.earned.v1 | [Link](services/driver-service/INTEGRATION.md) |
| courier-service | 2 | Kotlin/Spring | identity, branch, restaurant | food.order.ready, vehicle.registered, configuration.updated | courier.created, courier.approved, courier.suspended, courier.location.updated.v1, delivery.courier.assigned.v1, delivery.dispatch.*.v1, delivery.pickup.v1, delivery.in_transit.v1, delivery.completed.v1, delivery.failed.v1 | [Link](services/courier-service/INTEGRATION.md) |
| pricing-service | 3 | Kotlin/Spring | configuration, customer (loyalty account) | configuration.updated, customer.segment.changed, customer.loyalty_account.changed | pricing.quote.created, pricing.rating_density.applied, pricing.loyalty_discount.applied, pricing.geo_config.updated, tax.calculated.v1, promotion.*.v1, loyalty.tier.changed.v1 | [Link](services/pricing-service/INTEGRATION.md) |
| payment-service | 3 | Kotlin/Spring | Payment Provider (46 gateways), ledger, customer, courier, driver, restaurant, trip, food-order | customer.suspended, trip.completed, delivery.completed, payment.captured, payment.refund.completed, trip.reward.granted, trip.reward.reversed, courier.incentive.earned, driver.incentive.earned, merchant.suspended | payment.attempted, payment.authorized, payment.captured, payment.failed, payment.refund.completed, wallet.*.v1, ride.payment.*.v1, food.payment.*.v1, driver.earning.accrued, courier.earning.accrued, merchant.settlement.*.v1, merchant.payout.*.v1, payment.cod.collected.v1 | [Link](services/payment-service/INTEGRATION.md) |
| restaurant-service | 3 | Kotlin/Spring | customer, geolocation | merchant.approved, restaurant.created | restaurant.created, restaurant.approved, restaurant.online, restaurant.offline, merchant.*.v1, branch.*.v1, menu.*.v1, inventory.*.v1, staff.*.v1 | [Link](services/restaurant-service/INTEGRATION.md) |
| trip-service | 4 | Kotlin/Spring | driver, customer, geolocation, pricing | ride.request.created (own consumer via saga), customer.created, dispatch.matched, driver.location.updated, configuration.updated | trip.started, trip.arrived, trip.completed, trip.cancelled, trip.reward.granted, trip.reward.reversed, ride.request.*.v1, scheduled_ride.due.v1, ride.safety.*.v1, review.submitted.v1, review.aggregated.v1 (trip slice), trip.review.read.v1 | [Link](services/trip-service/INTEGRATION.md) |
| food-order-service | 5 | Kotlin/Spring | customer, restaurant, geolocation, pricing, notification | checkout.completed (own consumer), branch.busy, menu.*.v1, restaurant.offline | food.order.*.v1, cart.*.v1, checkout.*.v1, review.submitted.v1, review.aggregated.v1 (food slice), food.review.read.v1 | [Link](services/food-order-service/INTEGRATION.md) |
| search-service | 6 | Kotlin/Spring | restaurant, geolocation, food-order, trip | restaurant.updated, menu.updated, merchant.updated, review.submitted (search slice), review.aggregated (search slice) | — | [Link](services/search-service/INTEGRATION.md) |
| reporting-service | 6 | Kotlin/Spring | (every service — read APIs) | every domain event | — | [Link](services/reporting-service/INTEGRATION.md) |

## Domain Clusters

### Platform Foundation (Tier 0-1)
**Must implement first** — core infrastructure services

1. **configuration-service** (Tier 0)
   - No dependencies
   - Provides configuration + feature flags to all services
   - [Tasks](services/configuration-service/README.md)

2. **api-gateway** (Tier 1)
   - Depends on: identity-service
   - Entry point for all external traffic
   - [Tasks](services/api-gateway/README.md)

3. **audit-service** (Tier 1)
   - No dependencies
   - Consumes all audit events
   - [Tasks](services/audit-service/README.md)

4. **identity-service** (Tier 1)
   - Depends on: Keycloak
   - Identity management for all services
   - [Tasks](services/identity-service/README.md)

5. **ledger-service** (Tier 1)
   - No dependencies
   - Financial ledger for all money movements
   - [Tasks](services/ledger-service/README.md)

6. **geolocation-service** (Tier 1)
   - Depends on: Map Provider
   - Geospatial queries, ETA, routes, zones, cities
   - [Tasks](services/geolocation-service/README.md)

7. **file-service** (Tier 1)
   - Depends on: S3, ClamAV
   - File storage for all services
   - [Tasks](services/file-service/README.md)

### Application Services (Tier 2-6)

8. **notification-service** (Tier 2) — templates + deliveries + absorbed provider ACL
9. **admin-service** (Tier 2) — operations console + absorbed support module
10. **fraud-risk-service** (Tier 2) — risk scoring + blocklists
11. **customer-service** (Tier 2) — customer + cross-persona + addresses + loyalty account
12. **driver-service** (Tier 2) — driver profile + KYC + online + location + match + incentives + vehicles
13. **courier-service** (Tier 2) — courier profile + dispatch + tracking + delivery
14. **pricing-service** (Tier 3) — pricing engine + tax + promotions + loyalty rules
15. **payment-service** (Tier 3) — payment intents + wallet + sagas + earnings + settlement + COD + 46-gateway registry
16. **restaurant-service** (Tier 3) — merchant + restaurant + branch + menu + inventory + staff
17. **trip-service** (Tier 4) — trip + ride-request + scheduled + safety + history + trip reviews
18. **food-order-service** (Tier 5) — food order + cart + checkout + queue + food reviews
19. **search-service** (Tier 6) — search index + search reviews
20. **reporting-service** (Tier 6) — read models + data lake ingestion

## Removed services (consolidated per ADR-0017)

The following 38 services have been absorbed; their former
integration points are listed in the absorbing service's
`INTEGRATION.md`:

``customer-service` (addresses)`, ``reporting-service` (data lake)`, ``restaurant-service` (branch)`,
``food-order-service` (cart)`, ``food-order-service` (checkout)`, ``notification-service` (provider ACL)`,
``courier-service` (dispatch)`, ``payment-service` (courier earnings)`,
``courier-service` (tracking)`, ``courier-service` (delivery)`, ``driver-service` (dispatch)`,
``driver-service` (availability)`, ``payment-service` (driver earnings)`,
``driver-service` (incentives)`, ``driver-service` (location)`,
``geolocation-service` (ETA/routing)`, ``configuration-service` (flags)`,
``payment-service` (food saga)`, ``restaurant-service` (inventory)`,
``pricing-service` (loyalty rules) / `customer-service` (account)`, ``restaurant-service` (menu)`, ``restaurant-service` (merchant)`,
``pricing-service` (promotion)`, ``food-order-service` (queue)`,
``payment-service` (merchant settlement)`, ``restaurant-service` (staff)`,
``trip-service` / `food-order-service` / `search-service` (review projections)`, ``trip-service` (history)`,
``payment-service` (ride saga)`, ``trip-service` (ride-request)`,
``trip-service` (safety)`, ``trip-service` (scheduled)`, ``admin-service` (support module)`,
``pricing-service` (tax)`, ``customer-service` (cross-persona profile)`, ``driver-service` (vehicles)`,
``payment-service` (wallet)`, ``geolocation-service` (zones)`.

See [`MIGRATION_HUB.md`](MIGRATION_HUB.md) for the per-capability
mapping and the six-month compatibility window.