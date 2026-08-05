# Aggressive domain consolidation: 58 services → 20 services

## Locked decisions

- Build a **20-service domain architecture**.
- Retain familiar **domain service names**.
- Keep `identity-service`, `file-service`, and `audit-service` unchanged.
- Fully merge obsolete service documentation, verify preservation, then delete obsolete service directories.
- Preserve one `payment-service` for all operational payment concerns.
- Preserve an independent `ledger-service` for immutable double-entry accounting; “all payment in one service” does not collapse the audit ledger into payment.

## Final active service catalog

1. `identity-service` — unchanged; sole Keycloak bridge.
2. `file-service` — unchanged; sole storage-driver boundary.
3. `audit-service` — unchanged; immutable audit chain.
4. `api-gateway` — public ingress, auth enforcement, routing, throttling.
5. `customer-service` — absorbs `user-profile-service` and `address-service`; owns customer identity projection, profile/preferences/devices, addresses, privacy lifecycle, loyalty account/tier/points/frequent zones.
6. `driver-service` — absorbs `driver-availability-service`, `driver-location-service`, `dispatch-service`, `driver-incentive-service`, and `vehicle-service`; owns driver/KYC, vehicles, availability, location ingest, ride matching, deal bids, and incentive evaluation. Earnings remain in payment.
7. `trip-service` — absorbs `ride-request-service`, `scheduled-ride-service`, `ride-safety-service`, and `ride-history-service`; owns request-to-trip lifecycle, scheduling, safety/SOS/share-trip, history projection, guaranteed-reward decisions, and trip-side reviews.
8. `pricing-service` — absorbs `tax-service`, `promotion-service`, and `loyalty-service` pricing-rule capabilities; owns quote/fare/delivery fee, tax computation/snapshots, promotions/redemptions, rating-density, geo overrides, and loyalty pricing decisions. Customer loyalty balance/profile state is exposed through the customer module, with one explicitly documented writer chosen during merge.
9. `restaurant-service` — absorbs `merchant-service`, `branch-service`, `menu-service`, `inventory-service`, `restaurant-staff-service`, and restaurant operational capabilities; owns merchant legal/operator data, restaurants, branches, menus/catalog, inventory, staff/RBAC, hours, availability, and operator views.
10. `food-order-service` — absorbs `cart-service`, `checkout-service`, `restaurant-order-mgmt-service`, and non-payment order orchestration; owns cart → checkout session → order → preparation/ready/delivery-request lifecycle, order snapshots, cancellation triggers, and food-side reviews. Payment authorization/capture/refunds remain in payment.
11. `courier-service` — absorbs `courier-dispatch-service`, `courier-tracking-service`, and `delivery-service`; owns courier/KYC, availability, location ingest, delivery matching/deals, pickup/delivery lifecycle, proof of delivery, and domain confirmation of COD collection. Earnings and COD money state remain in payment.
12. `payment-service` — absorbs `ride-payment-integration-service`, `food-payment-integration-service`, `wallet-service`, `driver-earnings-service`, `courier-earnings-service`, and `restaurant-settlement-service`; owns the 46-gateway registry, intents/methods/attempts, authorization/capture/void/refund/dispute, ride/food sagas, wallet balances/holds/transactions, tips, driver/courier earnings and withdrawals, merchant payables/settlements/disputes/payouts, COD payment state/reconciliation, and operational financial reconciliation.
13. `ledger-service` — unchanged in responsibility; sole immutable double-entry journal/chart-of-accounts authority and accounting audit layer.
14. `geolocation-service` — absorbs `eta-routing-service` and `zone-service`; owns geocoding/reverse-geocoding, PostGIS zones/geofences, routing/ETA/provider ACLs, spatial cache, and geo configuration projection.
15. `notification-service` — absorbs `communication-gateway-service`; owns immutable template-version snapshots, preferences, orchestration, delivery attempts, and all channel-provider adapters. Preserve `template_version_snapshot_id` on every publication.
16. `configuration-service` — absorbs `feature-flag-service`; owns versioned configuration, sticky flags/experiments, kill switches, lookup administration/projection, and signed update streams.
17. `search-service` — remains the specialized cross-domain search/index context; absorbs discovery projections formerly embedded in restaurant/menu/customer-facing flows, without becoming a transactional writer.
18. `fraud-risk-service` — remains an independent scoring/model context; payment remains the only writer of chargeback/payment financial state.
19. `admin-service` — remains the management plane and SUPER_ADMIN preset owner; absorbs platform-control administration but keeps identity-service as the sole Keycloak bridge and preserves mandatory break-glass co-signing.
20. `reporting-service` — absorbs `analytics-service` and report/read-model projections; owns warehouse ingestion, operational reporting, reconciliations, dashboards, and analytical exports without writing transactional domain state.

`support-service` and `review-rating-service` are removed: support ticket/case workflows move into `admin-service` as a separately permissioned support module; ride review data moves into `trip-service`, food/restaurant/courier review data into `food-order-service`, and aggregate discovery projections go to `search-service`.

## Internal scaling rule

A surviving service is one bounded-context product and one public service identity, but it may deploy independently scalable internal workers from the same versioned release where workload profiles differ. Document separate Kubernetes Deployments/HPA signals for:

- driver location ingestion and matching workers,
- courier location ingestion and delivery matching workers,
- notification channel workers,
- reporting/warehouse consumers,
- payment saga, payout, reconciliation, and gateway webhook workers,
- geolocation/routing workers.

This preserves hot-path and Kafka-lag scaling without restoring obsolete public microservice boundaries.

## Implementation steps

### 1. Supersede the partial 44-service artifacts

- Amend ADR-0016 and `MIGRATION_HUB.md` to make the 44-service model an intermediate, superseded consolidation stage.
- Add a new ADR for the approved 20-service target and a complete migration hub mapping all 58 original services to 20 survivors.
- Correct the current contradictory state: files claiming 44 while 58 directories and most catalogs still show 58.

### 2. Establish a capability-preservation matrix

Before deletion, inventory each source service’s:

- owned entities/tables/partition rules and retention,
- APIs and authorization scopes,
- produced/consumed events,
- workflows/state machines/failure compensation,
- accounting postings and reconciliation,
- config/feature-flag keys,
- SLAs, scaling triggers, secrets, provider ACLs, and OSS dependencies.

Map every item to exactly one survivor and place the mapping in the migration hub. Resolve overlaps explicitly, especially loyalty state, reviews, support permissions, COD, payment-vs-ledger ownership, and restaurant/order transitions.

### 3. Rewrite the 17 changed survivor suites

Keep the three locked suites (`identity-service`, `file-service`, `audit-service`) unchanged. Update all applicable artifacts for the other 17 survivors:

- `README.md`, `BRD.md`, `SRS.md`, `ERD.md`, `INTEGRATION.md`, `WORKFLOWS.md`, `TECH.md`, `PLAN.md`, and `SKELETON.*`;
- plus `payment-service/GATEWAYS.md` without altering the canonical 46-gateway registry.

Append consolidation sections rather than renumbering established sections/deep links. Add merged capability requirements, schema migration maps, compatibility endpoints/events, internal modules/workers, deployment isolation, security, and tests.

### 4. API, event, and data compatibility

- Preserve legacy endpoint paths as compatibility routes on survivors where externally consumed.
- Preserve legacy event names and payload schemas, but change producer ownership to the survivor; dual-publish canonical replacements for at least six months when names change.
- Preserve UUIDs, aggregate IDs, quote/tax/config/template snapshots, idempotency keys, correlation/causation chains, ledger references, and immutable adjustment history.
- Move source tables into survivor schemas through additive forward-only migrations and backfills; do not use cross-schema foreign keys or direct cross-service DB access.
- Preserve canonical RANGE-by-time partitioning, composite parent PKs, pre-creation, retention, and `pg_inherits` verification for moved high-volume tables.

### 5. Payment and accounting invariants

- `payment-service` is the sole operational money writer and gateway/payout/COD/wallet/saga owner.
- `ledger-service` remains the sole double-entry writer.
- `pricing-service` remains the source of price/tax/discount snapshots; payment captures those immutable snapshots rather than recomputing.
- `fraud-risk-service` advises; payment owns financial decisions and loss/provision state.
- Preserve driver/courier/merchant payables, wallet liability, tips, gateway fees, tax liabilities, incentives/rewards, refunds, chargebacks, COD, multi-currency, period close, and all existing reconciliation semantics in `ACCOUNTING_WORKFLOWS.md`.

### 6. Cross-cutting architecture rewrite

Update all canonical architecture sources, including service count, diagrams, ownership, dependencies, events, APIs, consistency, deployment, security, configuration, isolation, failure handling, observability, validation, and ADR index:

- `docs/README.md`, `docs/services/README.md`
- `MICROSERVICES_MAP.md`, `DOMAIN_MAP.md`, `CONTEXT_MAP.md`, `ARCHITECTURE.md`, `SYSTEM_OVERVIEW.md`
- `DATA_OWNERSHIP.md`, `EVENT_ARCHITECTURE.md`, `DATABASE_ARCHITECTURE.md`, `CONSISTENCY_STRATEGY.md`
- `SERVICE_INTEGRATION_MATRIX.md`, `SERVICE_ISOLATION.md`, `FAILURE_HANDLING.md`, `DOWNSTREAM_ERROR_CATALOG.md`
- `SECURITY_ARCHITECTURE.md`, `KEYCLOAK_ARCHITECTURE.md`, `DEPLOYMENT_ARCHITECTURE.md`, `CONFIGURATION_ARCHITECTURE.md`, `API_STANDARDS.md`, `OBSERVABILITY.md`, `VALIDATION_REPORT.md`.

### 7. Rewrite workflows and shared feature hubs

Update every cross-service workflow:

- ride, food order, payment, refund, driver, courier, merchant, safety, and accounting.

Update shared hubs:

- `DEAL_FEATURE.md`: driver boundary → driver-service; courier boundary → courier-service; food boundary → food-order-service; pricing remains fare authority.
- `LOOKUPS.md`: configuration-service manages lookups; every service keeps local projection/copy as established; no cross-service FKs.
- `OSS_DEPENDENCIES.md`, platform baseline/modules/autoconfiguration/testing/versioning.
- Notification immutable snapshot chain and template seeds.
- SUPER_ADMIN preset to exactly 20 active `<service>.admin` scopes, with time-bounded aliases for removed scopes and mandatory break-glass co-signer.

### 8. Update plans and deployment model

Synchronize `MASTER_PLAN.md`, `IMPLEMENTATION_PHASES.md`, `PLAN_INDEX.md`, summaries, recommendations, and legacy-plan disclaimers:

- 20 active services,
- migration order and cutover waves,
- language/runtime consolidation decisions,
- service identities, scopes, Vault/KMS paths, network policies,
- surviving Helm releases with internal worker Deployments,
- HPA/PDB/SLO changes and rollback gates.

### 9. Verify, then delete 38 obsolete service directories

Delete only after every unique contract is represented in a survivor or the migration hub. Removed directories:

- `address-service`, `analytics-service`, `branch-service`, `cart-service`, `checkout-service`, `communication-gateway-service`, `courier-dispatch-service`, `courier-earnings-service`, `courier-tracking-service`, `delivery-service`, `dispatch-service`, `driver-availability-service`, `driver-earnings-service`, `driver-incentive-service`, `driver-location-service`, `eta-routing-service`, `feature-flag-service`, `food-payment-integration-service`, `inventory-service`, `loyalty-service`, `menu-service`, `merchant-service`, `promotion-service`, `restaurant-order-mgmt-service`, `restaurant-settlement-service`, `restaurant-staff-service`, `review-rating-service`, `ride-history-service`, `ride-payment-integration-service`, `ride-request-service`, `ride-safety-service`, `scheduled-ride-service`, `support-service`, `tax-service`, `user-profile-service`, `vehicle-service`, `wallet-service`, and `zone-service`.

Surviving directories total exactly 20.

### 10. Validation gates

- Exactly 20 service directories; each survivor has the required suite and skeleton.
- The three locked suites have no content changes.
- Every removed-service reference is explicitly historical/migration/compatibility—not active.
- No duplicate active data/event ownership, cross-service FK, direct DB access, or synchronous dependency cycle.
- Every event producer/consumer and state-changing API is registered, versioned, authorized, idempotent, and has failure semantics.
- Money conservation, immutable ledger, partitioning, snapshot, payout/refund/COD, and reconciliation invariants pass.
- Mermaid diagrams, links, section anchors, service counts, roles/scopes, config keys, OSS references, and phase plans are consistent.
- `VALIDATION_REPORT.md` records measured counts, grep/link/diagram results, compatibility windows, remaining risks, and explicit pass/fail evidence.