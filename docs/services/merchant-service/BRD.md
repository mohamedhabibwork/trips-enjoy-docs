# merchant-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and rules
for the `merchant-service` — the canonical owner of the **merchant
aggregate** (the legal entity that contracts with the platform). It is
read by:

- Product managers and business owners when scoping onboarding
  changes.
- Engineering leads when planning the service's roadmap.
- Compliance and legal teams when reviewing KYC and payout flows.
- Admin and support teams when designing operator consoles.

It informs decisions on KYC policy, payout eligibility, suspension
triggers, and the merchant lifecycle from intake to closure.

## 2. Business Context

The platform's food-delivery product requires a contractual partner —
the merchant — to operate one or more restaurant brands. The merchant
is the entity that:

- Is screened for sanctions and AML compliance.
- Files and remits taxes on food sales.
- Receives payouts for completed orders.
- Is suspended for quality, compliance, or fraud reasons.

A merchant is **not** a restaurant. A merchant may own several
restaurants (a holding), and a single restaurant may operate several
branches. This separation exists so that:

- Legal, tax, and banking are managed at the merchant level (the
  contracting party).
- Operational, brand, and menu management happens at the restaurant
  level.
- Physical logistics happens at the branch level.

Without this service, the platform would conflate legal and operational
concerns, leading to audit failures, payout disputes, and inability to
operate multi-brand merchants.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reduce merchant onboarding time to < 48 hours for ≥ 90% of applications | `merchant_approval_seconds` (P90) < 172,800 s |
| BR--002 | Achieve ≥ 99% KYC document compliance at first submission | `kyc_completeness_at_submit` ≥ 0.99 |
| BR--003 | Detect sanctioned or high-risk merchants at intake in 100% of cases | `sanctions_match_rate` = 1.00 (no false negatives allowed) |
| BR--004 | Enable weekly or bi-weekly payout to 100% of approved merchants | `payout_config_completeness` = 1.00 for `merchant.state = approved` |
| BR--005 | Suspend a merchant and cascade to restaurants within 60 seconds of admin action | `suspension_propagation_seconds` (P95) < 60 s |
| BR--006 | Provide a complete audit trail for every merchant state change | `audit_completeness` = 1.00 |
| BR--007 | Reject invalid or fraudulent merchant applications at intake | `fraudulent_application_rejection_rate` ≥ 0.99 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Merchant Owner | Signatory | smooth onboarding, predictable payouts |
| Merchant Finance | Payouts recipient | accurate and timely bank transfers |
| Merchant Ops | Day-to-day contact | clear status, fast admin responses |
| Platform Compliance | KYC/AML reviewer | complete document trail, sanction match |
| Platform Finance | Payouts | valid bank account, no holds without cause |
| Platform Trust & Safety | Quality / fraud | ability to suspend fast and audit fully |
| Platform Engineering | Builder | clean contracts, no cross-service leakage |
| Product (Food) | Roadmap | faster onboarding, lower drop-off |
| Legal | Policy | auditable approval/rejection process |
| Tax authority (external) | Compliance | access to merchant tax records (read-only) |

## 5. Actors / Personas

- **Merchant Owner**: a small-business owner signing up to run a
  restaurant on the platform. They have legal authority and provide
  identity, tax, and banking documents. They want to be approved
  quickly and to be able to update their contact info without a
  full re-onboarding.
- **Merchant Finance Contact**: a person at the merchant (often
  different from the owner) responsible for bank account changes and
  payout configuration. They have scoped read access to settlements
  (delegated to `restaurant-settlement-service`).
- **Platform Admin**: an internal user who reviews KYC, approves or
  rejects merchants, and can suspend or close them. They are
  accountable for the decisions and must provide a reason.
- **Platform Compliance Analyst**: a specialized admin focused on
  KYC/AML review, with read access and the ability to flag merchants
  for enhanced due diligence.
- **Downstream Service (e.g. `restaurant-service`)**: a system actor
  that reads the merchant to validate that a new restaurant can be
  created under it.

## 6. Business Capabilities

- **KYC intake and document collection**: capture legal name, legal
  form, country of registration, tax IDs, owner identity, bank
  account, and required supporting documents.
- **Sanctions and AML screening**: screen merchant legal name, owner
  name, and country against sanctions lists at intake and on every
  update.
- **Tax registration management**: store merchant tax IDs and
  certificates; support multiple jurisdictions.
- **Banking and payout configuration**: store a primary bank account;
  support secondary accounts; allow payout hold/unhold.
- **Contact management**: maintain primary, secondary, and finance
  contacts with notification preferences.
- **Lifecycle management**: drive the merchant through
  `pending_review → approved → suspended → approved` or
  `pending_review → rejected → pending_review` or
  `approved → closed`.
- **Admin review queue**: expose a list view and per-merchant review
  view for admin and compliance users.
- **Audit and reporting**: every state change and admin action is
  recorded; reports are available for compliance, finance, and
  product.
- **Payout eligibility gating**: signal to
  `restaurant-settlement-service` whether a merchant is eligible
  for payouts (via `merchant.payout.hold.v1` and merchant state).

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST support merchant onboarding (legal entity creation) with multi-step KYC intake | MUST | Onboarding policy |
| BR--011 | The service MUST store tax registration (ID, jurisdiction, certificate) per merchant | MUST | Tax policy |
| BR--012 | The service MUST store at least one verified bank account per approved merchant | MUST | Payout policy |
| BR--013 | The service MUST emit a `merchant.*.v1` event for every state change | MUST | Event architecture |
| BR--014 | The service MUST support merchant suspension by an admin with a required reason code | MUST | Trust & Safety |
| BR--015 | The service MUST support merchant re-instatement (suspended → approved) with a required reason | MUST | Trust & Safety |
| BR--016 | The service MUST support permanent merchant closure (approved → closed) with reason | MUST | Lifecycle policy |
| BR--017 | The service MUST allow a merchant owner to update contact information without re-onboarding | MUST | Product |
| BR--018 | The service MUST allow resubmission after rejection without creating a new merchant | SHOULD | Product |
| BR--019 | The service MUST support payout hold and unhold at the merchant level | MUST | Finance / Trust & Safety |
| BR--020 | The service MUST integrate with a sanctions screening provider at intake and on key updates | MUST | Compliance |
| BR--021 | The service MUST integrate with a bank account validator on bank account changes | MUST | Finance |
| BR--022 | The service MUST be able to cascade suspension to all merchants owned by a suspended user | MUST | Trust & Safety |
| BR--023 | The service MUST provide a list endpoint for admin review with status and country filters | MUST | Admin UX |
| BR--024 | The service MUST soft-delete merchants (never hard-delete) to preserve financial history | MUST | Data retention |
| BR--025 | The service MUST support multi-currency merchants (one primary currency, optional per-branch) | SHOULD | International expansion |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A merchant is eligible to be approved only if it has: ≥ 1 contact, ≥ 1 verified bank account, ≥ 1 KYC document scanned clean, and a non-blocking sanctions result. | enforced server-side in `approve` |
| BR--031 | A merchant can be in `pending_review` for at most 90 days before auto-expiry. | cron job sets `expired` |
| BR--032 | Suspension requires a reason code drawn from a fixed enum (`quality`, `fraud`, `compliance`, `payment_dispute`, `legal_order`). | enum stored in `configuration` |
| BR--033 | A suspended merchant cannot transition to `closed` without re-instatement first. | enforced server-side |
| BR--034 | A rejected merchant may be re-submitted; the previous rejection reason is preserved in the audit log. | no state collision |
| BR--035 | The merchant's primary bank account cannot be changed while a payout is in flight. | bank update is queued |
| BR--036 | A `merchant_owner` may update only their own merchant. | resource-level check |
| BR--037 | A `platform_admin` cannot suspend a merchant without a reason; the reason is logged. | required, ≤ 500 chars |
| BR--038 | Once a merchant is `closed`, no further state transitions are allowed. | terminal state |
| BR--039 | Sanctions re-screening runs on every legal name or owner change. | automatic |

## 9. Assumptions

- `identity-service` and Keycloak are available and provide a
  verified `kc_sub` for every merchant owner.
- A KYC document scanning provider is contracted and accessible via
  `file-service` for malware/virus scanning.
- A bank account validator provider is contracted.
- A sanctions screening provider is contracted.
- The merchant's primary currency is determined at onboarding; the
  service does not perform currency conversion.
- The merchant's banking and tax details are stable enough that
  updates are infrequent (single-digit per year per merchant).
- An internal admin team exists and is staffed to review KYC
  applications within the 48-hour SLA.

## 10. Constraints

- **Regulatory**: must comply with KYC/AML regulations in every
  operating country (e.g. FinCEN in the US, FCA in the UK, SAMA in
  KSA). Country-specific document checklists are externalized to
  `configuration-service` to allow updates without a code change.
- **PCI scope**: must remain out of PCI scope. The service stores
  bank account numbers (IBAN) but never card numbers. Card data is
  handled exclusively by the payment provider.
- **GDPR / data residency**: merchant PII (legal name, owner name,
  tax ID, bank details) is classified `confidential` and stored with
  column-level encryption; cross-border transfers are restricted to
  approved jurisdictions.
- **Audit**: every state change and admin action must be captured in
  `audit-service`; the audit log is immutable.
- **Independent deploy**: the service must be deployable independently
  of all other services. No shared migrations; no shared schemas.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | Keycloak subject lookup; user suspension cascade |
| `file-service` | service | KYC document storage; virus scan status |
| `restaurant-service` | service | reads merchant for restaurant creation |
| `restaurant-staff-service` | service | reads merchant for staff scoping |
| `restaurant-settlement-service` | service | reads payout config; receives hold events |
| `configuration-service` | service | document checklists, reason enums, SLA |
| `notification-service` | service | sends lifecycle messages |
| `audit-service` | service | receives audit events |
| `feature-flag-service` | service | rollout of auto-approval |
| KYC / sanctions provider | external | screening at intake |
| Bank account validator | external | IBAN validity and ownership |
| Tax authority lookup | external | VAT/GSTIN verification (optional) |
| Vault | infra | secrets, encryption keys |

## 12. Business Workflows

- **Merchant Onboarding (KYC)**: end-to-end intake, document upload,
  screening, admin review, approval.
- **Merchant Re-submission after Rejection**: owner fixes the issue
  and resubmits; previous review notes are preserved.
- **Merchant Suspension**: admin or cascade suspension; cascading
  events to all restaurants and to `restaurant-settlement-service`.
- **Merchant Re-instatement**: after a quality issue is resolved,
  admin re-instates; payout hold is lifted.
- **Merchant Closure**: terminal; no further transitions.
- **Payout Hold / Unhold**: finance or admin sets a hold on
  payouts; settlement is paused.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Sanctions match found at intake**: merchant is auto-rejected and
  flagged for compliance; the owner is notified via
  `notification-service`.
- **Bank account validation failure**: the owner is asked to correct
  via `notification-service`; the merchant remains in
  `pending_review` until the bank account is valid.
- **KYC document virus scan positive**: the document is quarantined
  and the owner is notified; the merchant remains in `pending_review`.
- **Owner user suspended**: if `payout_hold_on_owner_suspend` is
  true, the merchant is auto-suspended.
- **Admin error (e.g. accidental suspension)**: another admin
  re-instates within the grace period; the reason is preserved in
  the audit log.

## 14. Success Criteria

- 100% of KYC applications are screened against sanctions before
  approval.
- 100% of merchant state changes are emitted as events and persisted
  in `audit-service`.
- 100% of approved merchants have a verified primary bank account on
  file at the time of approval.
- P90 onboarding time ≤ 48 hours (BR--001).
- P95 suspension propagation ≤ 60 seconds (BR--005).

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Onboarding time (P90) | ≤ 48 h | `merchant_approval_seconds` histogram |
| Auto-approval rate (low-risk) | ≥ 70% | `merchants_auto_approved_total / merchants_created_total` |
| KYC document rejection rate | ≤ 15% | `kyc_documents_rejected_total / kyc_documents_uploaded_total` |
| Sanctions match false negative | 0 | reconciliation job vs. provider report |
| Suspension to cascade propagation (P95) | ≤ 60 s | synthetic probe |
| Payout hold effective (P95) | ≤ 60 s | `merchant.payout.hold.v1` → `restaurant-settlement-service` lag |
| KYC provider availability | ≥ 99.5% | `kyc_provider_call_total{status}` |
| Bank validator availability | ≥ 99.5% | `bank_validator_call_total{status}` |

## 16. Acceptance Criteria

- AC-1: A new merchant can submit a KYC application with all
  required fields and documents in ≤ 30 minutes (operator time).
- AC-2: An admin can approve or reject a merchant in a single
  action; the action is recorded in the audit log with reason.
- AC-3: A `merchant.suspended.v1` event is observed by
  `restaurant-service` within 60 seconds of admin suspension.
- AC-4: A merchant in `suspended` state cannot be used to create a
  new restaurant (`POST /v1/restaurants` returns 409).
- AC-5: A merchant in `closed` state is read-only; all writes return
  410.
- AC-6: A rejected merchant can be re-submitted without creating a
  new record.
- AC-7: The admin can place a payout hold; settlement is paused
  within 60 seconds.
- AC-8: PII fields (legal name, tax ID, bank account) are stored
  encrypted; a security review confirms.
- AC-9: All admin actions emit an `admin.audit.merchant.*` event
  consumed by `audit-service`.
- AC-10: The service meets its SLO of 99.95% availability in
  production.

---

## See also

### Sibling docs for this service

- [`README.md`](./README.md) — purpose, bounded context, responsibilities
- [`BRD.md`](./BRD.md) — business requirements
- [`SRS.md`](./SRS.md) — functional + non-functional requirements
- [`ERD.md`](./ERD.md) — data model (entities, relationships)
- [`INTEGRATION.md`](./INTEGRATION.md) — inter-service contracts (APIs, events, sagas)
- [`WORKFLOWS.md`](./WORKFLOWS.md) — operational workflows (happy paths, failure modes)
- [`TECH.md`](./TECH.md) — technology profile (runtime, libraries, data layer, admin endpoints, RBAC)

### Platform-wide

- [`../../shared/README.md`](../../shared/README.md) — `platform-spring-boot-starter` shared library (the single source of cross-cutting code for all Spring Boot services in the platform)
- [`../RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — platform-wide technology map (language, framework, version baseline, admin/RBAC pattern)
- [`../../README.md`](../../README.md) — services overview (the catalog of all 58 services)
- [`../../../main.md`](../../../main.md) — top-level platform specification (architecture, Keycloak, PostgreSQL 18, messaging, observability baseline)

