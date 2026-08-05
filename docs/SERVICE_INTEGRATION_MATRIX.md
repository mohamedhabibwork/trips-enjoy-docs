# Service Integration Matrix

> **Purpose:** Complete integration dependency mapping for all 58 microservices
> **Updated:** 2026-07-29

## Quick Reference

| Service | Tier | Tech | Sync Deps | Async Consumes | Async Produces | Doc Link |
|---------|------|------|-----------|----------------|----------------|----------|
| configuration-service | 0 | Kotlin/Spring | None | customer.segment.changed, zone.surge.updated, feature_flag.updated | configuration.updated, configuration.rolled_back | [Link](services/configuration-service/INTEGRATION.md) |
| feature-flag-service | 0 | Kotlin/Spring | identity | customer.segment.changed, customer.created | feature_flag.updated, feature_flag.disabled | [Link](services/feature-flag-service/INTEGRATION.md) |
| api-gateway | 1 | Go/Envoy | identity, All services | identity.session.revoked, identity.user.suspended, configuration.updated | audit.api.request, gateway.rate_limit.exceeded | [Link](services/api-gateway/INTEGRATION.md) |
| audit-service | 1 | Go | None | All *.audit.* events + high-value events (payment.captured, ledger.posted, trip.reward.granted, trip.reward.reversed, pricing.geo_config.updated) | audit.consumer.lag, audit.export.completed | [Link](services/audit-service/INTEGRATION.md) |
| identity-service | 1 | Node/TS | Keycloak | customer.created, driver.created, courier.created, merchant.created, configuration.updated | identity.user.created, identity.user.suspended, identity.session.revoked | [Link](services/identity-service/INTEGRATION.md) |
| ledger-service | 1 | Node/TS | None | payment.captured, wallet.credited, merchant.settlement.accrued, courier.earning.accrued, trip.reward.granted, trip.reward.reversed | ledger.posted, ledger.audit.reconciliation_drift | [Link](services/ledger-service/INTEGRATION.md) |
| geolocation-service | 1 | Go | Map Provider | None | geolocation.geocoded, geolocation.eta.computed | [Link](services/geolocation-service/INTEGRATION.md) |
| zone-service | 1 | Kotlin/Spring | geolocation | None | zone.updated, zone.surge.updated | [Link](services/zone-service/INTEGRATION.md) |
| file-service | 1 | Go | S3, ClamAV | None | file.uploaded, file.scanned, file.deleted | [Link](services/file-service/INTEGRATION.md) |
| communication-gateway-service | 1 | Go | FCM, APNs, Twilio, AWS SES | None | comms.sms.sent, comms.email.sent, comms.push.sent | [Link](services/communication-gateway-service/INTEGRATION.md) |
| user-profile-service | 2 | Kotlin/Spring | identity | identity.user.created | user.profile.updated | [Link](services/user-profile-service/INTEGRATION.md) |
| customer-service | 2 | Kotlin/Spring | identity, payment | identity.user.created, payment.method.saved | customer.created, customer.updated, customer.suspended | [Link](services/customer-service/INTEGRATION.md) |
| driver-service | 2 | Kotlin/Spring | identity, vehicle, geolocation | vehicle.registered, document.expiring | driver.created, driver.approved, driver.suspended | [Link](services/driver-service/INTEGRATION.md) |
| courier-service | 2 | Kotlin/Spring | identity, vehicle | vehicle.registered | courier.created, courier.approved, courier.suspended | [Link](services/courier-service/INTEGRATION.md) |
| vehicle-service | 2 | Kotlin/Spring | identity | None | vehicle.registered, vehicle.approved, vehicle.insurance.expired | [Link](services/vehicle-service/INTEGRATION.md) |
| address-service | 2 | Kotlin/Spring | geolocation | None | address.created, address.updated, address.deleted | [Link](services/address-service/INTEGRATION.md) |
| tax-service | 2 | Kotlin/Spring | configuration | configuration.updated | tax.calculated | [Link](services/tax-service/INTEGRATION.md) |
| promotion-service | 2 | Kotlin/Spring | customer, configuration | customer.segment.changed | promotion.redeemed, promotion.created | [Link](services/promotion-service/INTEGRATION.md) |
| notification-service | 2 | Kotlin/Spring | communication-gateway | trip.completed, food.order.placed, payment.failed, trip.reward.granted, trip.reward.reversed | notification.sent, notification.failed | [Link](services/notification-service/INTEGRATION.md) |
| admin-service | 2 | Kotlin/Spring | All services | All service audit events | admin.action.performed | [Link](services/admin-service/INTEGRATION.md) |
| support-service | 2 | Kotlin/Spring | customer, driver, courier, payment | payment.disputed, customer.suspended | support.ticket.opened, support.ticket.resolved | [Link](services/support-service/INTEGRATION.md) |
| fraud-risk-service | 2 | Python/FastAPI | identity | identity.session.created, payment.attempted | fraud.risk.scored, fraud.account.blocked | [Link](services/fraud-risk-service/INTEGRATION.md) |
| pricing-service | 3 | Kotlin/Spring | configuration, tax, promotion | configuration.updated | pricing.quote.created, pricing.rating_density.applied, pricing.loyalty_discount.applied, pricing.geo_config.updated | [Link](services/pricing-service/INTEGRATION.md) |
| payment-service | 3 | Kotlin/Spring | Payment Provider | customer.suspended | payment.attempted, payment.authorized, payment.captured, payment.failed, payment.refund.completed | [Link](services/payment-service/INTEGRATION.md) |
| wallet-service | 3 | Kotlin/Spring | payment | payment.captured, trip.reward.granted, trip.reward.reversed | wallet.credited, wallet.debited, wallet.held, wallet.released | [Link](services/wallet-service/INTEGRATION.md) |
| merchant-service | 3 | Kotlin/Spring | identity | None | merchant.created, merchant.approved, merchant.suspended | [Link](services/merchant-service/INTEGRATION.md) |
| restaurant-service | 3 | Kotlin/Spring | merchant, geolocation | merchant.approved | restaurant.created, restaurant.approved, restaurant.online, restaurant.offline | [Link](services/restaurant-service/INTEGRATION.md) |
| branch-service | 3 | Kotlin/Spring | restaurant, geolocation, zone | restaurant.created, zone.updated | branch.created, branch.updated, branch.hours.changed, branch.busy | [Link](services/branch-service/INTEGRATION.md) |
| driver-availability-service | 3 | Go | driver | driver.approved, driver.suspended | driver.availability.online, driver.availability.offline | [Link](services/driver-availability-service/INTEGRATION.md) |
| driver-location-service | 3 | Go | None | driver.availability.online | driver.location.updated | [Link](services/driver-location-service/INTEGRATION.md) |
| courier-tracking-service | 3 | Go | None | courier.availability.online | courier.location.updated | [Link](services/courier-tracking-service/INTEGRATION.md) |
| eta-routing-service | 3 | Go | Map Provider | None | eta.computed, route.computed | [Link](services/eta-routing-service/INTEGRATION.md) |
| menu-service | 4 | Kotlin/Spring | restaurant, tax, inventory | restaurant.created, inventory.item.86d | menu.created, menu.updated, menu.item.unavailable | [Link](services/menu-service/INTEGRATION.md) |
| inventory-service | 4 | Kotlin/Spring | menu | menu.item.unavailable | inventory.item.out_of_stock, inventory.item.restocked | [Link](services/inventory-service/INTEGRATION.md) |
| cart-service | 4 | Kotlin/Spring | customer, menu, pricing, promotion | menu.item.price.changed, restaurant.offline | cart.created, cart.updated, cart.checked_out, cart.abandoned | [Link](services/cart-service/INTEGRATION.md) |
| ride-request-service | 4 | Kotlin/Spring | customer, pricing, dispatch, driver-availability | dispatch.matched | ride.request.created, ride.request.matched, ride.request.cancelled | [Link](services/ride-request-service/INTEGRATION.md) |
| trip-service | 4 | Kotlin/Spring | driver, courier, ride-request, eta-routing | ride.request.matched, driver.location.updated | trip.started, trip.arrived, trip.completed, trip.cancelled, trip.reward.granted, trip.reward.reversed | [Link](services/trip-service/INTEGRATION.md) |
| dispatch-service | 4 | Kotlin/Spring | driver-availability, driver-location, ride-request | ride.request.created, driver.location.updated | dispatch.matched, dispatch.no_driver | [Link](services/dispatch-service/INTEGRATION.md) |
| driver-earnings-service | 4 | Kotlin/Spring | payment, wallet | ride.payment.completed, trip.completed, trip.reward.granted, trip.reward.reversed | driver.earning.accrued, driver.withdrawal.completed | [Link](services/driver-earnings-service/INTEGRATION.md) |
| restaurant-staff-service | 4 | Kotlin/Spring | identity, restaurant | restaurant.created | staff.invited, staff.activated, staff.deactivated | [Link](services/restaurant-staff-service/INTEGRATION.md) |
| review-rating-service | 4 | Kotlin/Spring | customer, driver, courier, restaurant | trip.completed, food.order.delivered | review.submitted, review.aggregated, review.zone_aggregated | [Link](services/review-rating-service/INTEGRATION.md) |
| loyalty-service | 4 | Kotlin/Spring | customer, trip, food-order | trip.completed, food.order.completed | loyalty.points.earned, loyalty.tier.changed, loyalty.frequent_zone.aggregated | [Link](services/loyalty-service/INTEGRATION.md) |
| scheduled-ride-service | 4 | Kotlin/Spring | ride-request | None | scheduled_ride.due | [Link](services/scheduled-ride-service/INTEGRATION.md) |
| ride-safety-service | 4 | Kotlin/Spring | trip, notification, geolocation | trip.started | ride.safety.sos, ride.safety.share, ride.safety.incident | [Link](services/ride-safety-service/INTEGRATION.md) |
| checkout-service | 5 | Kotlin/Spring | cart, pricing, customer, address, payment | cart.updated, pricing.quote.created | checkout.completed, checkout.failed | [Link](services/checkout-service/INTEGRATION.md) |
| food-order-service | 5 | Kotlin/Spring | restaurant, branch, customer, pricing | checkout.completed, branch.busy | food.order.placed, food.order.accepted, food.order.rejected | [Link](services/food-order-service/INTEGRATION.md) |
| restaurant-order-mgmt-service | 5 | Kotlin/Spring | food-order, menu, restaurant | food.order.placed | food.order.preparing, food.order.ready | [Link](services/restaurant-order-mgmt-service/INTEGRATION.md) |
| courier-dispatch-service | 5 | Python/FastAPI | courier, courier-tracking, food-order | food.order.ready, courier.availability.online, courier.location.updated | delivery.courier.assigned, delivery.dispatch.no_courier | [Link](services/courier-dispatch-service/INTEGRATION.md) |
| delivery-service | 5 | Kotlin/Spring | courier, branch, food-order, customer | delivery.courier.assigned, courier.location.updated | delivery.pickup, delivery.in_transit, delivery.completed, delivery.failed | [Link](services/delivery-service/INTEGRATION.md) |
| ride-payment-integration-service | 5 | Kotlin/Spring | payment, wallet, ledger, pricing, driver-earnings | trip.completed, payment.authorized, payment.captured | ride.payment.completed, ride.payment.failed | [Link](services/ride-payment-integration-service/INTEGRATION.md) |
| food-payment-integration-service | 5 | Kotlin/Spring | payment, wallet, ledger, restaurant-settlement, courier-earnings | delivery.completed, payment.captured | food.payment.completed, merchant.settlement.created | [Link](services/food-payment-integration-service/INTEGRATION.md) |
| driver-incentive-service | 5 | Python/FastAPI | configuration, trip | trip.completed | driver.incentive.earned | [Link](services/driver-incentive-service/INTEGRATION.md) |
| courier-earnings-service | 5 | Kotlin/Spring | payment, wallet | delivery.completed, food.payment.completed | courier.earning.accrued, courier.withdrawal.completed | [Link](services/courier-earnings-service/INTEGRATION.md) |
| restaurant-settlement-service | 5 | Kotlin/Spring | ledger, payment | food.payment.completed, merchant.suspended | merchant.settlement.accrued, merchant.payout.completed | [Link](services/restaurant-settlement-service/INTEGRATION.md) |
| search-service | 6 | Kotlin/Spring | restaurant, menu, OpenSearch | restaurant.updated, menu.updated, merchant.updated | None | [Link](services/search-service/INTEGRATION.md) |
| analytics-service | 6 | Kotlin/Spring | S3, Snowflake | All topic streams | None | [Link](services/analytics-service/INTEGRATION.md) |
| reporting-service | 6 | Python/FastAPI | All services | All service domain events | None | [Link](services/reporting-service/INTEGRATION.md) |
| ride-history-service | 6 | Kotlin/Spring | trip, ride-payment-integration, review-rating | trip.completed, ride.payment.completed, review.submitted | None | [Link](services/ride-history-service/INTEGRATION.md) |

## Domain Clusters

### Platform Foundation (Tier 0-1)
**Must implement first** - Core infrastructure services

1. **configuration-service** (Tier 0)
   - No dependencies
   - Provides configuration to all services
   - [Tasks](services/configuration-service/README.md)

2. **feature-flag-service** (Tier 0)
   - No dependencies
   - Provides feature flags to all services
   - [Tasks](services/feature-flag-service/README.md)

3. **api-gateway** (Tier 1)
   - Depends on: identity-service
   - Entry point for all external traffic
   - [Tasks](services/api-gateway/README.md)

4. **audit-service** (Tier 1)
   - No dependencies
   - Consumes all audit events
   - [Tasks](services/audit-service/README.md)

5. **identity-service** (Tier 1)
   - Depends on: Keycloak
   - Identity management for all services
   - [Tasks](services/identity-service/README.md)

6. **ledger-service** (Tier 1)
   - No dependencies
   - Financial ledger for all money movements
   - [Tasks](services/ledger-service/README.md)

7. **geolocation-service** (Tier 1)
   - Depends on: Map Provider
   - Geospatial queries for all services
   - [Tasks](services/geolocation-service/README.md)

8. **zone-service** (Tier 1)
   - Depends on: geolocation-service
   - Service zones and boundaries
   - [Tasks](services/zone-service/README.md)

9. **file-service** (Tier 1)
   - Depends on: S3, ClamAV
   - File storage for all services
   - [Tasks](services/file-service/README.md)

10. **communication-gateway-service** (Tier 1)
    - Depends on: FCM, APNs, Twilio, AWS SES
    - Multi-channel messaging
    - [Tasks](services/communication-gateway-service/README.md)

