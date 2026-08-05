# fraud-risk-service — Business Requirements Document

## 1. Document Purpose

This document is the authoritative statement of *what*
`fraud-risk-service` must do for the business. It is read by
the fraud team, the security team, the platform architecture
team, the ML engineering team, the payment team, the
compliance team, and the service's engineering team. It
informs the scoring models, the blocklist policy, the
right-to-erasure flow for fraud data, the high-value
threshold tuning, and the integration with payments and
identity.

## 2. Business Context

The platform handles millions of payments a day and millions
of logins a day. A small percentage of those are fraudulent
— stolen cards, account takeover, bonus abuse, money
laundering, collusion between driver and rider. The cost of
fraud is direct (chargebacks, lost goods) and indirect
(brand damage, regulatory fines).

`fraud-risk-service` is the platform's real-time defense.
It scores every login, every payment, and (curated) every
dispatch match, and produces a risk score that downstream
services use to allow, challenge (require MFA), or block.
The service is Tier-1: a regression here stops payments and
logins, and a wrong score either lets fraud through
(direct loss) or blocks a legitimate user (customer trust
incident).

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Score every login, payment, and dispatch match in < 200ms P99 | API P99 ≤ 200ms |
| BR--002 | Reduce fraud loss by ≥ 30% within 6 months of going live | fraud loss trending down month over month |
| BR--003 | Block a confirmed-fraud account within 60s of detection | 100% of confirmed-fraud blocks within 60s |
| BR--004 | False-positive rate (legitimate user blocked) < 0.5% | measured weekly |
| BR--005 | Honor right-to-erasure for fraud data within 24h of request | 100% within 24h |
| BR--006 | Allow model deploys without downtime (blue/green) | model deploys complete in < 1 min with no score loss |
| BR--007 | Support A/B testing of new models (shadow / 1% / 10% / 50% / 100%) | any model can be deployed at any traffic % |
| BR--008 | Provide a complete scoring audit log (every score, every input, every decision) for fraud analysts | 100% of scores in audit log |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Fraud team (analysts) | primary | review scores, manage blocklists, tune thresholds |
| ML engineering | owner | train and deploy models |
| Security | reviewer | high-severity alerts, account block actions |
| Payments | consumer | "is this payment fraudulent?" |
| Identity | consumer | "is this login account takeover?" |
| Dispatch | consumer | "is this driver / rider location fake?" |
| Customer Support | consumer | "why was this user blocked? can we unblock?" |
| Compliance / Legal | reviewer | right-to-erasure, anti-money-laundering |
| Finance | reviewer | fraud loss, chargeback rate |

## 5. Actors / Personas

- **Fraud analyst (L1, L2)**: reviews recent scores, manages
  blocklists (adds / removes entries), tunes thresholds.
- **ML engineer**: trains new models, deploys them via the
  model registry.
- **Customer (rider / diner)**: is scored on login and
  payment; may be challenged (MFA) or blocked.
- **Driver / Courier**: is scored on dispatch; may be
  blocked from accepting rides / deliveries.
- **Security on-call**: paged on high-severity fraud
  signals (e.g. a coordinated attack).

## 6. Business Capabilities

- **Real-time scoring** (login, payment, dispatch).
- **Device fingerprinting** (browser, app, device).
- **Velocity checks** (attempts per minute / hour / day).
- **Blocklists** (email, phone, IP, device, card BIN,
  region).
- **Scoring models** (rule-based + ML; registry;
  A/B testing).
- **Account block actions** (delegated to `identity-service`
  and the relevant profile service).
- **Model evaluation** (precision, recall, F1, AUC,
  false-positive rate).
- **Right-to-erasure** for fraud data.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST score every `identity.session.created.v1`, `payment.attempted.v1`, and `dispatch.matched.v1` (curated) event in < 200ms P99. | MUST | platform architecture, payments |
| BR--011 | The service MUST produce a score in `[0, 1]` and a decision (`allow`, `challenge`, `block`) per the configured thresholds. | MUST | fraud, security |
| BR--012 | The service MUST support A/B testing of models (shadow / 1% / 10% / 50% / 100%) without downtime. | MUST | ML engineering |
| BR--013 | The service MUST support blue/green model deploys. | MUST | ML engineering |
| BR--014 | The service MUST maintain blocklists (email, phone, IP, device, card BIN, region) with sub-millisecond lookups. | MUST | fraud, security |
| BR--015 | The service MUST block a confirmed-fraud account within 60s of detection, emitting `fraud.account.blocked.v1`. | MUST | security |
| BR--016 | The service MUST emit `fraud.risk.scored.v1` for every score, consumed by the originating service. | MUST | integration |
| BR--017 | The service MUST support an admin override (`POST /v1/allowlist`) for false positives. | MUST | support, customer trust |
| BR--018 | The service MUST honor right-to-erasure for fraud data within 24h of request from `support-service`. | MUST | GDPR, PDPL |
| BR--019 | The service MUST maintain a device fingerprint cache with sub-millisecond lookups. | MUST | fraud, performance |
| BR--020 | The service MUST perform velocity checks (per IP / phone / email / card / device per minute / hour / day). | MUST | fraud |
| BR--021 | The service MUST record every score with all inputs and the decision in an audit log. | MUST | compliance, audit |
| BR--022 | The service MUST support rule-based + ML scoring (the active model is configurable per event type). | MUST | ML engineering |
| BR--023 | The service MUST alert on high-severity fraud signals (coordinated attack, sudden spike in blocks). | MUST | security |
| BR--024 | The service MUST NOT store raw card numbers (use BIN + last4 only). | MUST | PCI |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--020 | A score ≥ `threshold.block` results in `block`; ≥ `threshold.challenge` results in `challenge`; otherwise `allow`. | thresholds are configurable per event type |
| BR--021 | A `block` decision triggers `fraud.account.blocked.v1` and a P1 `support-service` ticket. | |
| BR--022 | Velocity limits are checked before the model is invoked; a velocity breach returns `block` immediately. | |
| BR--023 | A blocklist hit returns `block` immediately. | |
| BR--024 | The active model for an event type is determined by the configuration (`fraud_risk.scoring.<event_type>.model_id`). | |
| BR--025 | Right-to-erasure purges scores, blocklist entries (if user-specific), and device fingerprints; models are de-identified and not erased. | |
| BR--026 | Model artifacts are stored in S3 with server-side encryption; the schema stores only metadata. | |

## 9. Assumptions

- The fraud team reviews scores and blocklists regularly.
- The ML team retrains models monthly (or more often if
  drift is detected).
- The volume of scoring events is bursty (e.g. during a
  holiday sale) but bounded; we can scale horizontally.
- The platform's compliance team has approved the
  right-to-erasure policy (with the model retention
  exception).

## 10. Constraints

- **Latency**: P99 score ≤ 200ms (login); ≤ 200ms (payment,
  with feature fetch).
- **Compliance**: GDPR / PDPL right-to-erasure (with
  model retention exception); PCI (no PAN).
- **Reliability**: 99.95% SLO; no score loss on model
  deploy.
- **Auditability**: every score, every block, every model
  deploy in the audit log.

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | producer of `identity.session.created.v1`; consumer of `fraud.account.blocked.v1` |
| `payment-service` | service | producer of `payment.attempted.v1`; consumer of `fraud.risk.scored.v1` |
| `dispatch-service` | service | producer of `dispatch.matched.v1` (curated); consumer of `fraud.risk.scored.v1` |
| `customer-service` | service | consumer of `fraud.account.blocked.v1` |
| `driver-service` | service | consumer of `fraud.account.blocked.v1` |
| `courier-service` | service | consumer of `fraud.account.blocked.v1` |
| `support-service` | service | consumer of `fraud.risk.scored.v1` (for false-positive investigation); producer of right-to-erasure requests |
| `configuration-service` | service | thresholds, active models |
| `feature-flag-service` | service | A/B routing |
| `reporting-service` | service | read aggregated features |
| `audit-service` | consumer | reads `fraud.*.v1` events |
| `analytics-service` | consumer | reads `fraud.*.v1` events |
| PostgreSQL 18 | infra | core storage |
| Redis 7 | infra | blocklists, device fingerprints, in-flight dedup |
| Kafka | infra | events |
| S3 | infra | model artifacts |
| Vault | infra | signing keys |

## 12. Business Workflows

- **Real-time login scoring** — see `WORKFLOWS.md` §1.
- **Real-time payment scoring** — see `WORKFLOWS.md` §2.
- **Account block on confirmed fraud** — see
  `WORKFLOWS.md` §3.
- **Admin allowlist override (false positive)** — see
  `WORKFLOWS.md` §4.
- **Model deploy (blue/green)** — see `WORKFLOWS.md` §5.
- **Right-to-erasure** — see `WORKFLOWS.md` §6.

## 13. Exception Workflows

- **Model inference fails**: fall back to the rule-based
  scoring (a simpler model that is always available).
  Emit a high-severity alert.
- **Blocklist cache down**: fall back to PostgreSQL
  (slower but correct). Emit a warn.
- **All scoring paths fail**: return `challenge` (the
  safest default — the user is asked for MFA).
- **Right-to-erasure partial failure**: retry with
  backoff; on persistent failure, escalate to compliance.

## 14. Success Criteria

- 100% of logins scored in < 200ms P99.
- 100% of payments scored in < 200ms P99.
- Fraud loss reduced by ≥ 30% within 6 months.
- False-positive rate < 0.5% per week.
- 100% of right-to-erasure requests within 24h.
- 0 score loss on model deploy.
- 0 PAN ever stored.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Score P99 (login) | ≤ 100ms | `fraud_score_seconds{event_type=login}` P99 |
| Score P99 (payment) | ≤ 200ms | same, payment |
| Score P99 (dispatch) | ≤ 200ms | same, dispatch |
| Fraud loss ($/month) | trending down | finance dashboard |
| False-positive rate | < 0.5% | `fraud_blocks_total{decision=block, confirmed_fraud=false} / total_logins` |
| Right-to-erasure within 24h | 100% | `erasure_completed_total{on_time=true} / erasure_total` |
| Model deploy duration | < 1 min | `model_deploy_seconds` |
| Block action latency | ≤ 60s | `fraud_block_seconds` P95 |

## 16. Acceptance Criteria

- All 16 business requirements implemented and verified by
  automated tests.
- A simulated login with a stolen credential in staging
  results in a `block` decision and a `fraud.account.blocked.v1`
  event.
- A model deploy in staging completes in < 1 min with no
  score loss (verified by a continuous score generator).
- A right-to-erasure request from `support-service` results
  in the user's scores and blocklist entries being
  deleted within 24h.
- A `POST /v1/score` request for a known-fraudulent
  payment in staging returns `decision=block` and score
  ≥ 0.9.
- A `POST /v1/score` request for a known-legitimate
  payment in staging returns `decision=allow` and score
  ≤ 0.1.
- The right-to-erasure flow does not affect the model
  (the model is de-identified).

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

