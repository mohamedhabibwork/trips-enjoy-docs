# Service consolidation and payment centralization plan

## Locked target architecture

Adopt **domain consolidation**, **full operational payment ownership**, and **retire-but-preserve** migration documentation.

### Surviving consolidated services

1. **`courier-service`** absorbs:
   - `courier-dispatch-service`
   - `courier-tracking-service`
   - Courier profile/KYC, shifts, availability, location, delivery matching, and operational courier lifecycle from the existing `courier-service`.
   - Financial responsibilities from `courier-earnings-service` move to `payment-service`, not to `courier-service`.

2. **`driver-service`** absorbs:
   - `driver-availability-service`
   - `driver-location-service`
   - `driver-incentive-service` operational incentive definition/evaluation
   - `dispatch-service`
   - Driver profile/KYC, availability, location, ride matching, deal bidding, incentive eligibility/evaluation, and driver lifecycle.
   - Earnings, payable balances, withdrawals, and financial incentive postings move to `payment-service`.

3. **`food-order-service`** absorbs:
   - `restaurant-order-mgmt-service` order-queue and restaurant-side order transition responsibilities
   - Non-payment orchestration formerly documented in `food-payment-integration-service`
   - Canonical food order, customer/restaurant order views, queue timers, preparation, ready-for-dispatch, cancellation triggers, and Make-a-Deal food boundary.
   - Authorization, capture, refunds, COD, tips, and payment saga state move to `payment-service`.

4. **`restaurant-service`** absorbs:
   - `restaurant-staff-service`
   - Restaurant aggregate, staff/RBAC, invitations/devices, restaurant lifecycle, and restaurant operational administration.
   - Settlement, merchant payable, disputes, payout scheduling, and bank-transfer state from `restaurant-settlement-service` move to `payment-service`.

5. **`payment-service`** absorbs:
   - `ride-payment-integration-service`
   - `food-payment-integration-service`
   - `wallet-service`
   - `driver-earnings-service`
   - `courier-earnings-service`
   - `restaurant-settlement-service`
   - COD collection/reconciliation payment state currently split with `delivery-service`
   - All operational money ownership: payment intents/methods/attempts, 46 gateway drivers, authorization/capture/void/refund/dispute, ride and food payment sagas, wallet balances/holds/transactions, tips, driver and courier earnings/payables/withdrawals, merchant settlements/payables/disputes/payout runs, payout execution, gateway reconciliation, operational payment reconciliation, and payment idempotency namespaces.

6. **`ledger-service` remains independent** as the only immutable double-entry accounting/audit truth. Pricing remains in `pricing-service`, tax computation remains in `tax-service`, risk scoring remains in `fraud-risk-service`, reporting remains in `reporting-service`, and domain services retain domain facts and payment references only.

## Compatibility policy

- Preserve existing public APIs and event names initially as compatibility aliases owned by the surviving service.
- Introduce canonical consolidated endpoints/events where needed; document old contracts as deprecated.
- Use additive schema migration, backfill, dual-read/dual-publish where necessary, reconciliation gates, and a minimum six-month event compatibility window per the event-versioning policy.
- Preserve aggregate IDs, idempotency keys, correlation/causation IDs, quote snapshots, ledger posting references, and immutable financial history.
- Do not create cross-service database foreign keys or direct database access.

## Documentation implementation

### 1. Add an architecture decision and migration hub

Create a new ADR and a shared consolidation document covering:

- The chosen boundaries and rejected alternatives.
- Before/after service map and revised service count.
- Capability-by-capability ownership matrix.
- Source/target schemas and data migration order.
- API/event compatibility matrix.
- Deployment, rollback, traffic cutover, shadow-read, reconciliation, and retirement phases.
- PCI/PII/KMS implications of placing wallets, payable ledgers, and payout methods in `payment-service`.
- Tier-0 scaling, blast-radius controls, modular internal boundaries, bulkheads, and independent workers within the consolidated payment deployment.

Update the ADR index and all navigation/index pages to link these documents.

### 2. Rewrite the five surviving service suites

Update every applicable artifact for `courier-service`, `driver-service`, `food-order-service`, `restaurant-service`, and `payment-service`:

- `README.md`: purpose, bounded context, owned/not-owned responsibilities, actors, dependencies, schemas, APIs, events, configuration, security, observability, scalability, deployment, accounting impact, permissions, OSS, lookups, and Deal participation.
- `BRD.md`: merged business goals, rules, actors, constraints, KPIs, and acceptance outcomes.
- `SRS.md`: merged functional/non-functional/security/data requirements with traceable IDs.
- `ERD.md`: consolidated logical schema, forward-only migration DDL, immutable/partitioned financial tables, source-schema mapping, retention, encryption, and reconciliation rules.
- `INTEGRATION.md`: canonical and compatibility APIs/events, idempotency, auth scopes, timeouts, failure translation, retries, DLQs, and contract migration.
- `WORKFLOWS.md`: merged state machines, success paths, compensation, timeout, replay, and failure recovery.
- `TECH.md`: internal module boundaries, driver interfaces, dependency bundle, migration tooling, tests, and extractability stubs.
- `PLAN.md`: phased implementation, data migration, contract rollout, validation, cutover, and retirement tasks.
- `SKELETON.*`: update project/module dependencies and extractability layout to match the target runtime.
- Preserve established section numbers and append new sections rather than renumbering existing deep-linked sections.

For `payment-service`, retain `GATEWAYS.md` as the canonical 46-gateway registry and expand the internal architecture into modular contexts such as intent/gateway, orchestration, wallet, earnings, settlement, payout, COD, reconciliation, and compatibility adapters—one deployable service, not one undifferentiated module.

### 3. Retire and preserve superseded service suites

For each absorbed service, preserve its directory but mark every entry point as retired/superseded and point to the target service and migration hub:

- `courier-dispatch-service`
- `courier-tracking-service`
- `courier-earnings-service`
- `driver-availability-service`
- `driver-location-service`
- `driver-incentive-service`
- `driver-earnings-service`
- `dispatch-service`
- `restaurant-order-mgmt-service`
- `restaurant-staff-service`
- `restaurant-settlement-service`
- `food-payment-integration-service`
- `ride-payment-integration-service`
- `wallet-service`

Retired docs remain historical migration references, are excluded from active service counts/deployment plans, and clearly state whether each capability moved to a domain service or `payment-service`. Their old API/event/schema contracts remain documented only under compatibility and migration headings.

### 4. Update all affected neighboring service docs

Update every producer, consumer, caller, and permission surface affected by the merges, including at minimum:

- `delivery-service`: domain collection confirmation only; `payment-service` owns COD payment state and reconciliation.
- `trip-service`, `ride-request-service`, `checkout-service`, `customer-service`, `merchant-service`, `branch-service`, `menu-service`, `pricing-service`, `tax-service`, `fraud-risk-service`, `ledger-service`, `reporting-service`, `audit-service`, `analytics-service`, `notification-service`, `support-service`, `admin-service`, `configuration-service`, `feature-flag-service`, `identity-service`, `ride-history-service`, and other grep-discovered consumers/producers.
- Correct the existing checkout authorization endpoint drift.
- Repoint dependencies, consumed/produced event ownership, API callers, scopes, configuration keys, accounting pointers, and `Explicitly NOT Owned` sections.

### 5. Rewrite canonical architecture and catalog documents

Synchronize:

- `docs/README.md` and `docs/services/README.md`
- `architecture/MICROSERVICES_MAP.md`
- `architecture/DATA_OWNERSHIP.md`
- `architecture/DOMAIN_MAP.md`
- `architecture/CONTEXT_MAP.md`
- `architecture/ARCHITECTURE.md`
- `architecture/SYSTEM_OVERVIEW.md`
- `architecture/EVENT_ARCHITECTURE.md`
- `SERVICE_INTEGRATION_MATRIX.md`
- `architecture/CONSISTENCY_STRATEGY.md`
- `architecture/DATABASE_ARCHITECTURE.md`
- `architecture/SERVICE_ISOLATION.md`
- `architecture/FAILURE_HANDLING.md`
- `architecture/DOWNSTREAM_ERROR_CATALOG.md`
- `architecture/SECURITY_ARCHITECTURE.md`
- `architecture/KEYCLOAK_ARCHITECTURE.md`
- `architecture/DEPLOYMENT_ARCHITECTURE.md`
- `architecture/CONFIGURATION_ARCHITECTURE.md`
- `architecture/API_STANDARDS.md`
- `architecture/OBSERVABILITY.md`
- `architecture/VALIDATION_REPORT.md`

The active catalog/count must exclude retired services while the historical index still identifies them as superseded. Reconcile known count, tier, runtime, and event-name drift while editing these canonical sources.

### 6. Rewrite all cross-service workflows

Update all eight workflow documents, with complete actor/sequence/state/failure diagrams:

- `RIDE_WORKFLOWS.md`
- `FOOD_ORDER_WORKFLOWS.md`
- `PAYMENT_WORKFLOWS.md`
- `REFUND_WORKFLOWS.md`
- `DRIVER_WORKFLOWS.md`
- `COURIER_WORKFLOWS.md`
- `MERCHANT_WORKFLOWS.md`
- `SAFETY_WORKFLOWS.md`

`ACCOUNTING_WORKFLOWS.md` remains the single four-layer accounting view and will be rewritten so operational payment ownership is concentrated in `payment-service`, while pricing/tax, immutable ledger, and reporting remain separate layers. Preserve all driver, courier, merchant, wallet-liability, gateway-fee, refund, chargeback, fraud-loss, guaranteed-reward, tax, and reconciliation semantics.

### 7. Preserve cross-cutting features

- Update `shared/DEAL_FEATURE.md`: driver deal/dispatch capability now lives inside `driver-service`; courier deal/dispatch inside `courier-service`; food customer boundary remains `food-order-service`; pricing remains sole fare authority.
- Update `shared/LOOKUPS.md` and each surviving schema copy; resolve no-cross-service-FK wording without changing the established lookup catalog model.
- Update `shared/OSS_DEPENDENCIES.md`, service recommendation/version authority, per-service TECH §11, and skeleton dependencies.
- Preserve `admin-service` SUPER_ADMIN preset semantics, revise the active `<service>.admin` scope inventory, retain break-glass co-signing, and document aliases for retired scopes during migration.
- Preserve notification immutable `template_version_snapshot_id` audit binding.
- Preserve canonical time-range partitioning rules and composite primary keys for every moved financial/high-volume table.

### 8. Update plans, phases, permissions, and deployment

Synchronize `MASTER_PLAN.md`, `IMPLEMENTATION_PHASES.md`, `PLAN_INDEX.md`, current summaries, and legacy plan disclaimers with:

- Revised active service count and phases.
- Surviving deployment units and retired units.
- Migration dependency order.
- Payment-service T0 capacity/SLA and internal worker isolation.
- Keycloak clients/scopes, admin preset aliases, Vault paths/KMS keys, PCI boundary, service accounts, NetworkPolicies, HPA/PDB, dashboards, alerts, audit topics, and disaster recovery.

### 9. Validation and consistency pass

Run repository-wide checks for:

- Every old service name classified as either migration/history/compatibility or removed from active architecture.
- Correct active service count and no duplicate active ownership.
- No synchronous dependency cycles and no cross-service DB access/FKs.
- Every produced event has a registered owner and consumers; compatibility events have deprecation windows.
- Every state-changing endpoint has idempotency and authorization rules.
- Financial invariants: money conservation, immutable adjustments, ledger-only double entry, quote/tax snapshots, exact-once composition, payout/refund/COD reconciliation.
- Partition DDL follows the canonical RANGE-by-time/composite-PK template.
- Mermaid diagrams, relative links, service indices, scopes, configuration keys, OSS references, and section anchors are valid.
- Required service artifact counts distinguish active versus retired services.
- Add migration, backfill, replay, dual-publish, contract, reconciliation, PCI/security, performance, and failure-injection test requirements to the relevant plans.

Finally, update `architecture/VALIDATION_REPORT.md` with the measured post-change counts, remaining compatibility risks, and explicit pass/fail evidence.