# Data Ownership

This is the **source-of-truth matrix** for the platform. It is the
authoritative answer to "who owns this entity?" If two services both
write a column, this matrix is wrong — file an issue.


```mermaid
flowchart LR
  subgraph "Customer / Person data"
    cust["customer-service<br/>(customer profile)"]
    user["`customer-service` (cross-persona profile)<br/>(common profile)"]
    addr["`customer-service` (addresses)<br/>(saved addresses)"]
  end
  subgraph "Driver / Courier"
    drv["driver-service<br/>(driver profile)"]
    cou["courier-service<br/>(courier profile)"]
    veh["`driver-service` (vehicles)<br/>(vehicles)"]
    davl["`driver-service` (availability)<br/>(online state)"]
    dloc["`driver-service` (location)<br/>(location stream)"]
    dearn["`payment-service` (driver earnings)<br/>(earnings ledger)"]
    cearn["`payment-service` (courier earnings)<br/>(earnings ledger)"]
  end
  subgraph "Merchant / Restaurant"
    merch["`restaurant-service` (merchant)"]
    rest["restaurant-service"]
    br["`restaurant-service` (branch)"]
    menu["`restaurant-service` (menu)"]
    rstaff["`restaurant-service` (staff)"]
    inv["`restaurant-service` (inventory)"]
  end
  subgraph "Order / Trip / Delivery"
    fos["food-order-service<br/>(order aggregate)"]
    rom["`food-order-service` (queue)<br/>(prep queue)"]
    cart["`food-order-service` (cart)"]
    co["`food-order-service` (checkout)"]
    rrs["`trip-service` (ride-request)"]
    trip["trip-service"]
    dsp["`driver-service` (dispatch)"]
    cdsp["`courier-service` (dispatch)"]
    del["`courier-service` (delivery)"]
    sched["`trip-service` (scheduled)"]
  end
  subgraph "Money"
    pay["payment-service<br/>(intents)"]
    wal["`payment-service` (wallet)<br/>(balances)"]
    led["ledger-service<br/>(double-entry)"]
    rpis["`payment-service` (ride saga)"]
    fpis["`payment-service` (food saga)"]
    rs["`payment-service` (merchant settlement)"]
  end
  subgraph "Platform"
    cfg["configuration-service"]
    ff["`configuration-service` (flags)"]
    audit["audit-service"]
    notif["notification-service"]
    id["identity-service<br/>(Keycloak adapter)"]
  end

  cust -.-> rrs
  cust -.-> cart
  addr -.-> rrs
  addr -.-> cart
  drv -.-> rrs
  drv -.-> trip
  cou -.-> del
  veh --> drv
  veh --> cou
  davl --> rrs
  dloc --> dsp
  dloc --> trip
  merch --> rest
  rest --> br
  br --> menu
  rstaff --> br
  inv --> fos
  cart --> fos
  co --> fos
  fos --> rom
  rom --> cdsp
  cdsp --> del
  cdsp --> cearn
  dsp --> trip
  dsp --> dearn
  rrs --> trip
  sched --> rrs
  rpis --> pay
  rpis --> dearn
  rpis --> led
  fpis --> pay
  fpis --> rs
  fpis --> led
  pay --> wal
  pay --> led
  id --> cust
  id --> drv
  id --> cou
  cfg -.-> pay
  ff -.-> pay
  audit -.-> pay
  notif -.-> pay
```

## The Rule

> **Exactly one service writes to each piece of data. Other services
> hold a denormalized view or a reference (ID) only.**

Cross-service references are stored as UUID columns **without** database
foreign keys. Consistency across services is achieved by:

- APIs (synchronous, request/response, with the calling service's
  responsibility to validate the reference exists and is current).
- Events (asynchronous propagation, with the consumer responsible for
  handling out-of-order and duplicate delivery).
- Reconciliation jobs (periodic, idempotent, repairing drift).

## Source-of-Truth Matrix

| Domain Entity | Owning Service | Database / Schema | Source of Truth | Referenced By | Sync Method |
|---------------|----------------|-------------------|------------------|---------------|-------------|
| Keycloak user | Keycloak (managed by `identity-service`) | Keycloak DB | Keycloak | All services with auth | Sync REST (token introspection); JWT for stateless verify |
| Identity reference (`identity_id`) | `identity-service` | `identity.identities` | `identity-service` | `customer-service`, `driver-service`, `courier-service`, ``restaurant-service` (merchant)` | Sync REST; events `identity.*.v1` |
| User profile (lang, prefs) | ``customer-service` (cross-persona profile)` | `user_profile.profiles` | ``customer-service` (cross-persona profile)` | `customer-service`, `notification-service` | Events `user.profile.*.v1` |
| Customer | `customer-service` | `customer.customers` | `customer-service` | ``trip-service` (ride-request)`, `food-order-service`, ``food-order-service` (cart)`, ``food-order-service` (checkout)`, `payment-service` (default method ref) | Events `customer.*.v1` + REST |
| Driver | `driver-service` | `driver.drivers` | `driver-service` | ``trip-service` (ride-request)`, `trip-service`, ``driver-service` (dispatch)`, ``driver-service` (availability)`, ``driver-service` (location)`, ``payment-service` (driver earnings)`, ``driver-service` (incentives)` | Events `driver.*.v1` + REST |
| Courier | `courier-service` | `courier.couriers` | `courier-service` | ``courier-service` (dispatch)`, ``courier-service` (delivery)`, ``payment-service` (courier earnings)`, ``courier-service` (tracking)` | Events `courier.*.v1` + REST |
| Vehicle | ``driver-service` (vehicles)` | `vehicle.vehicles` | ``driver-service` (vehicles)` | `driver-service`, `courier-service` | Events `vehicle.*.v1` + REST |
| Saved address | ``customer-service` (addresses)` | `address.addresses` | ``customer-service` (addresses)` | `customer-service` (default), ``food-order-service` (cart)`, ``food-order-service` (checkout)` | REST + `address.*.v1` |
| Geocode / ETA / route | `geolocation-service` (read) | `geolocation.cache` (cache only) | `geolocation-service` (stateless adapter) | ``geolocation-service` (ETA/routing)`, ``customer-service` (addresses)`, `trip-service`, ``courier-service` (delivery)`, ``trip-service` (ride-request)` | Sync REST (cached); events on cache invalidation |
| City / zone | ``geolocation-service` (zones)` | `zone.zones` | ``geolocation-service` (zones)` | `pricing-service`, ``driver-service` (dispatch)`, ``courier-service` (dispatch)`, ``trip-service` (ride-request)`, ``food-order-service` (cart)` | REST + `zone.*.v1` |
| Ride request | ``trip-service` (ride-request)` | `ride_request.requests` | ``trip-service` (ride-request)` | `trip-service`, ``driver-service` (dispatch)`, ``payment-service` (ride saga)`, ``trip-service` (history)` | Events `ride.request.*.v1` |
| Trip | `trip-service` | `trip.trips` | `trip-service` | ``payment-service` (ride saga)`, ``payment-service` (driver earnings)`, ``driver-service` (incentives)`, ``trip-service` / `food-order-service` / `search-service` (review projections)`, ``trip-service` (history)`, ``trip-service` (safety)` | Events `trip.*.v1` |
| Driver availability | ``driver-service` (availability)` | `driver_availability.availability` | ``driver-service` (availability)` | ``driver-service` (dispatch)`, ``driver-service` (location)` | Events `driver.availability.*.v1` |
| Driver location (current + recent trail) | ``driver-service` (location)` | `driver_location.locations` (partitioned by day) | ``driver-service` (location)` | ``driver-service` (dispatch)`, ``trip-service` (safety)`, ``geolocation-service` (ETA/routing)` (read) | Events `driver.location.*.v1` (curated stream) |
| Dispatch attempt | ``driver-service` (dispatch)` | `dispatch.attempts` | ``driver-service` (dispatch)` | ``trip-service` (ride-request)` (read) | Events `dispatch.*.v1` |
| ETA / route (cached) | ``geolocation-service` (ETA/routing)` (cache only) | `eta_routing.cache` | ``geolocation-service` (ETA/routing)` (stateless adapter) | `trip-service`, ``courier-service` (delivery)` | Sync REST (cached) |
| Driver earnings | ``payment-service` (driver earnings)` | `driver_earnings.earnings` | ``payment-service` (driver earnings)` | ``driver-service` (incentives)`, ``trip-service` (history)`, `reporting-service` | REST + `driver.earning.*.v1` |
| Driver incentive | ``driver-service` (incentives)` | `driver_incentive.incentives` | ``driver-service` (incentives)` | ``payment-service` (driver earnings)` | Events `driver.incentive.*.v1` |
| Scheduled ride job | ``trip-service` (scheduled)` | `scheduled_ride.jobs` | ``trip-service` (scheduled)` | ``trip-service` (ride-request)` | Events `scheduled_ride.due.v1` |
| Trip safety state | ``trip-service` (safety)` | `ride_safety.trips` | ``trip-service` (safety)` | `notification-service`, ``admin-service` (support module)` | REST + `ride.safety.*.v1` |
| Ride history (read model) | ``trip-service` (history)` | `ride_history.entries` | ``trip-service` (history)` | `customer-service` app, `driver-service` app, `admin-service` | Sync REST (read-only) |
| Merchant | ``restaurant-service` (merchant)` | `merchant.merchants` | ``restaurant-service` (merchant)` | `restaurant-service`, ``restaurant-service` (staff)`, ``payment-service` (merchant settlement)`, `admin-service` | REST + `merchant.*.v1` |
| Restaurant | `restaurant-service` | `restaurant.restaurants` | `restaurant-service` | ``restaurant-service` (branch)`, ``restaurant-service` (menu)`, `food-order-service`, ``food-order-service` (cart)`, `search-service` | REST + `restaurant.*.v1` |
| Branch | ``restaurant-service` (branch)` | `branch.branches` | ``restaurant-service` (branch)` | `food-order-service`, ``courier-service` (dispatch)`, ``food-order-service` (queue)` | REST + `branch.*.v1` |
| Restaurant staff | ``restaurant-service` (staff)` | `restaurant_staff.staff` | ``restaurant-service` (staff)` | ``food-order-service` (queue)` (RBAC), `admin-service` | REST + `staff.*.v1` |
| Menu (categories, products, modifiers) | ``restaurant-service` (menu)` | `menu.categories`, `menu.products`, `menu.modifiers` | ``restaurant-service` (menu)` | ``food-order-service` (cart)`, ``food-order-service` (checkout)`, `food-order-service`, `search-service`, ``restaurant-service` (inventory)` | REST + `menu.*.v1` |
| Inventory | ``restaurant-service` (inventory)` | `inventory.stock`, `inventory.unavailability` | ``restaurant-service` (inventory)` | ``restaurant-service` (menu)`, ``food-order-service` (cart)`, `food-order-service` | REST + `inventory.*.v1` |
| Cart | ``food-order-service` (cart)` | `cart.carts` | ``food-order-service` (cart)` | ``food-order-service` (checkout)`, `customer-service` (recent cart) | REST + `cart.*.v1` |
| Checkout session | ``food-order-service` (checkout)` | `checkout.sessions` | ``food-order-service` (checkout)` | `payment-service` (auth), `food-order-service` (creation) | REST + `checkout.*.v1` |
| Food order | `food-order-service` | `food_order.orders` | `food-order-service` | ``food-order-service` (queue)`, ``courier-service` (dispatch)`, ``courier-service` (delivery)`, ``payment-service` (food saga)`, `customer-service` (history), ``trip-service` / `food-order-service` / `search-service` (review projections)` | REST + `food.order.*.v1` |
| Restaurant order queue | ``food-order-service` (queue)` | `restaurant_order_mgmt.queue` | ``food-order-service` (queue)` | `food-order-service` (read), ``restaurant-service` (staff)` (UI) | REST + `food.order.*.v1` |
| Courier availability | `courier-service` (online) + ``courier-service` (dispatch)` (busy) | `courier.courier_state`, `courier_dispatch.assignments` | `courier-service` (online flag), ``courier-service` (dispatch)` (busy/assigned) | ``courier-service` (dispatch)`, ``courier-service` (tracking)` | Events `courier.availability.*.v1` |
| Courier location | ``courier-service` (tracking)` | `courier_tracking.locations` (partitioned by day) | ``courier-service` (tracking)` | ``courier-service` (dispatch)`, ``courier-service` (delivery)`, ``trip-service` (safety)` (read) | Events `courier.location.*.v1` |
| Delivery | ``courier-service` (delivery)` | `delivery.deliveries` | ``courier-service` (delivery)` | `food-order-service` (read), ``payment-service` (courier earnings)`, ``payment-service` (food saga)`, `customer-service` (history) | REST + `delivery.*.v1` |
| Courier earnings | ``payment-service` (courier earnings)` | `courier_earnings.earnings` | ``payment-service` (courier earnings)` | `reporting-service`, `courier-service` (UI) | REST + `courier.earning.*.v1` |
| Payment intent | `payment-service` | `payment.intents` | `payment-service` | ``payment-service` (ride saga)`, ``payment-service` (food saga)`, ``payment-service` (wallet)`, `customer-service` (history) | REST + `payment.*.v1` |
| Wallet | ``payment-service` (wallet)` | `wallet.wallets`, `wallet.holds` | ``payment-service` (wallet)` | `customer-service`, ``payment-service` (driver earnings)`, ``payment-service` (courier earnings)` | REST + `wallet.*.v1` |
| Ledger posting | `ledger-service` | `ledger.accounts`, `ledger.postings` | `ledger-service` | `reporting-service`, ``payment-service` (merchant settlement)`, audit | REST + `ledger.posted.v1` |
| Restaurant settlement | ``payment-service` (merchant settlement)` | `restaurant_settlement.payables`, `restaurant_settlement.payouts` | ``payment-service` (merchant settlement)` | ``restaurant-service` (merchant)` (UI), `reporting-service` | REST + `merchant.settlement.*.v1` |
| Promotion | ``pricing-service` (promotion)` | `promotion.campaigns`, `promotion.coupons`, `promotion.redemptions` | ``pricing-service` (promotion)` | ``food-order-service` (cart)`, `pricing-service` | REST + `promotion.*.v1` |
| Loyalty | ``pricing-service` (loyalty rules) / `customer-service` (account)` | `loyalty.accounts`, `loyalty.transactions` | ``pricing-service` (loyalty rules) / `customer-service` (account)` | `customer-service` (UI), `pricing-service` (read-only) | REST + `loyalty.*.v1` |
| Tax | ``pricing-service` (tax)` | `tax.rules`, `tax.exemptions` | ``pricing-service` (tax)` | `pricing-service`, ``restaurant-service` (menu)` | REST + `tax.*.v1` |
| Review / rating | ``trip-service` / `food-order-service` / `search-service` (review projections)` | `review.reviews` | ``trip-service` / `food-order-service` / `search-service` (review projections)` | `driver-service` (rating), `courier-service` (rating), `restaurant-service` (rating), `customer-service` (history) | REST + `review.*.v1` |
| Notification template | `notification-service` | `notification.templates` | `notification-service` | `notification-service` only (one writer) | Internal |
| Notification delivery state | `notification-service` | `notification.deliveries` | `notification-service` | ``admin-service` (support module)` (read) | REST |
| Communication send log | ``notification-service` (provider ACL)` | `comms_gateway.sends` | ``notification-service` (provider ACL)` | `notification-service` (state), ``admin-service` (support module)` (audit) | REST + `comms.*.sent.v1` |
| Configuration | `configuration-service` | `configuration.documents` (versioned) | `configuration-service` | Every service | REST + `configuration.updated.v1` |
| Feature flag | ``configuration-service` (flags)` | `feature_flag.flags` | ``configuration-service` (flags)` | Every service | REST + `feature_flag.updated.v1` |
| File metadata | `file-service` | `file.files` | `file-service` | `restaurant-service`, `driver-service`, `courier-service`, ``restaurant-service` (merchant)`, `customer-service` (KYC) | REST + `file.*.v1` |
| Search index doc | `search-service` | OpenSearch index owned by `search-service` | `search-service` | `customer-service` (UI), ``restaurant-service` (merchant)` (UI) | Sync REST query |
| Support ticket | ``admin-service` (support module)` | `support.tickets` | ``admin-service` (support module)` | `customer-service`, `driver-service`, `courier-service` (read), `admin-service` | REST + `support.*.v1` |
| Fraud risk score | `fraud-risk-service` | `fraud_risk.scores` | `fraud-risk-service` | `identity-service`, `payment-service`, ``driver-service` (dispatch)` (block) | REST + `fraud.*.v1` |
| Audit event | `audit-service` | `audit.events` (immutable, append-only) | `audit-service` (consumer; never producer of business events) | `admin-service`, ``admin-service` (support module)` | Consumes from Kafka |
| Report / OLAP view | `reporting-service` | `reporting.views` (read model) | `reporting-service` | `admin-service` (UI) | REST |

## Disallowed Patterns

The following are explicitly disallowed by the platform's data
ownership rules:

| Disallowed | Why | What to do instead |
|------------|-----|--------------------|
| Foreign key from one service's DB to another | Coupling; deploy ordering; outbox-free drift | UUID column without FK; consistency via events |
| Reading another service's DB directly | Hidden coupling; bypasses API contract | Sync REST or curated event |
| Writing to another service's DB | Violates ownership; bypasses service logic | Producer emits an event; the owner service consumes and applies |
| Two services writing the same column | Conflicting truth; race conditions | Pick an owner; the other is a derived read model |
| Storing PAN (raw card number) | PCI scope | Only the provider's tokenized reference |
| Storing the same PII in two services | Audit/GDPR complexity | Store the canonical record in the owner; reference by ID |
| Hard deletion of business records | Audit, support, fraud investigation | Soft delete with retention; purge job after retention window |

## Cross-Service Consistency Strategy Summary

| Cross-service invariant | Strategy | Tolerable lag |
|-------------------------|----------|---------------|
| Customer exists when a ride is requested | Sync API check; reject 404 if missing | Real-time |
| Driver belongs to a city that serves the pickup zone | Sync API check at dispatch time | Real-time |
| Restaurant is online when an order is placed | Sync API check at checkout | Real-time; UI also gates |
| Trip is paid after `trip.completed` | Saga (``payment-service` (ride saga)`) with outbox + ledger | Minutes |
| Order is paid + restaurant payable + courier earning after delivery | Saga (``payment-service` (food saga)`) | Minutes |
| Cancellation fee charged to wallet | Saga with outbox | Seconds to minutes |
| Promotion redemption counted once | Idempotency key on `promotion.redeem.v1` consumer | Eventual; reconciliation job catches duplicates |
| Driver location visible to dispatch | Event `driver.location.updated.v1` | Seconds (acceptable for matching) |
| Driver rating updated after trip | Event `trip.completed.v1` → ``trip-service` / `food-order-service` / `search-service` (review projections)` aggregates | Hours (acceptable) |
| Customer's ride history reflects completed trips | Read model ``trip-service` (history)` consumes `trip.completed.v1` and `ride.payment.completed.v1` | Minutes |
| Configuration change picked up by services | Long-poll + `configuration.updated.v1` event | Seconds |

## Reconciliation Jobs

A scheduled job in `reporting-service` (or a dedicated
`reconciliation-service` if volume justifies) runs daily to detect:

- Wallets whose balance doesn't equal sum of `ledger.postings` for the
  wallet.
- Food orders that are `delivered` without a `food.payment.completed`.
- Ride trips that are `completed` without a `ride.payment.completed`.
- Promotion redemptions that are counted twice.
- Stale `*.in_transit` deliveries that have not moved in N minutes.

Each drift finding opens a `support.ticket` (severity-tagged) and emits
a `reconciliation.drift.found.v1` event consumed by `admin-service`.