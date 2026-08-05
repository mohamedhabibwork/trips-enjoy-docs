# Tax Service — Business Requirements Document

## 1. Document Purpose

Read by finance, the tax / compliance team, the pricing-service
team, the food engineering team, and the tax-service engineering
team. It informs the design of the rule engine, the exemption
handling, the multi-jurisdiction support, and the operational SLOs.

## 2. Business Context

The platform operates in many jurisdictions, each with its own tax
rules (VAT, sales tax, GST, excise). The rules:

- Differ per country / region / city.
- Differ per product category (food vs. alcohol vs. ride fare).
- Allow exemptions (baby food, medicines, certain categories).
- Change infrequently but must be hot-reloadable.

This service exists so that **tax calculation is a single source of
truth** — consistent across ride and food, and reproducible from a
captured snapshot.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Reach 99.9% availability on the calculate path so tax never blocks a quote. | Availability SLO; P99 latency < 50ms (cached). |
| BR--002 | Support multiple jurisdictions (country, region, city). | Rule engine. |
| BR--003 | Support per-product tax codes. | Rule engine. |
| BR--004 | Support exemptions. | Rule engine. |
| BR--005 | Allow operators to change rules without code change. | All rules in `configuration-service` / this service. |
| BR--006 | Make every change attributable to a user with a reason. | 100% write attribution. |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Finance / Tax | owner | Correct tax calculation |
| Compliance | auditor | Full change history |
| Pricing | consumer | Low-latency tax lookup |
| Food engineering | consumer | Per-product tax code |
| Operations | operator | Hot-reload rules |

## 5. Actors / Personas

- **Operator (admin)** — opens the admin console, edits a rule,
  sets the reason, and saves.
- **`pricing-service`** — calls `POST /v1/tax/calculate` on every
  quote.
- **`menu-service`** — reads product tax codes when building a
  cart's preview.
- **Auditor** — searches the rule history.

## 6. Business Capabilities

- Jurisdiction rules (country, region, city, special zones).
- Product tax codes (food, alcohol, ride fare, delivery fee, tip).
- Exemptions (per jurisdiction × product).
- Tax calculation (rate, taxable amount, tax amount).
- Snapshot capture (the calculation returns the rules used).
- Hot-reload on rule change.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST support multiple jurisdictions (country, region, city). | MUST | Finance |
| BR--011 | The service MUST support per-product tax codes. | MUST | Finance |
| BR--012 | The service MUST support exemptions. | MUST | Finance |
| BR--013 | A change MUST propagate to consumers within 5 seconds. | MUST | Operations |
| BR--014 | Every change MUST be attributed to a user and carry a reason. | MUST | Compliance |
| BR--015 | The service MUST return a `snapshot` listing the rules used. | MUST | Finance / Audit |
| BR--016 | The service MUST emit `tax.calculated.v1` for every successful calculation. | MUST | Analytics |
| BR--017 | The service MUST support reverse-charge (B2B) where applicable. | SHOULD | Finance |
| BR--018 | The service MUST support multi-currency. | MUST | Finance |
| BR--019 | The service MUST support reduced rates (e.g. food at 9% vs. standard 21%). | MUST | Finance |
| BR--020 | The service MUST support inclusive / exclusive tax (price-with-tax vs. price-without-tax). | MUST | Finance |
| BR--021 | The service MUST support rounding rules per jurisdiction. | MUST | Finance |
| BR--022 | The service MUST support "destination" vs. "origin" tax (which side of the border pays). | MUST | Finance |
| BR--023 | The service MUST keep the full rule history for at least 7 years. | MUST | Compliance |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A jurisdiction is identified by `(country, region, city)`; the most specific match wins. | Standard. |
| BR--031 | A product tax code is identified by `code`; the code is mapped to a category. | Standard. |
| BR--032 | An exemption overrides the base rate for a `(jurisdiction, product_code)` pair. | Standard. |
| BR--033 | The tax is computed on the taxable amount (after discounts, before tips). | Standard. |
| BR--034 | The tax is rounded to the nearest minor unit per the jurisdiction's rounding rule. | Standard. |
| BR--035 | A calculation that finds no rule returns the default rate. | Standard. |

## 9. Assumptions

- The number of jurisdictions is bounded at < 1,000.
- The number of product tax codes is bounded at < 100.
- Rule changes are infrequent (a few per month).
- A jurisdiction's tax year is calendar year; rules are versioned
  with `effective_from` and `effective_to`.

## 10. Constraints

- The service must be hot-reloadable (a rule change is live in 5
  seconds).
- The service must be deployable without a code change for any new
  rule.
- The service must be deterministic (same input + same rules →
  same output).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `configuration-service` | service | Base rates |
| PostgreSQL 18 | database | Per-service schema `tax` |
| Redis | cache | Rule cache |
| Kafka | broker | Publishes + consumes |
| HashiCorp Vault | secrets | DB credentials |

## 12. Business Workflows

- Operator creates a jurisdiction (workflow 1).
- Operator creates a product tax code (workflow 2).
- Pricing service calculates tax (workflow 3).

## 13. Exception Workflows

- **No rule found** — return the default rate.
- **Configuration unreachable, cache cold** — 503 `CIRCUIT_OPEN`.
- **Exemption conflict** — the most specific rule wins.

## 14. Success Criteria

- 99.9% calculate availability.
- P99 calculate latency < 50ms (cached).
- 100% of writes attributed to a user with a reason.
- A rule change is live in < 5 seconds.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Calculate availability | 99.9% | Synthetic probes |
| P99 calculate latency | 50ms cached | RED metrics |
| Cache hit rate | ≥ 90% | Redis hit ratio |
| Write attribution coverage | 100% | Audit completeness |
| Median propagation latency | 2s | Event publish to consumer ack |

## 16. Acceptance Criteria

- A ride quote includes the correct tax for the pickup city.
- A food quote includes the correct tax for the delivery address.
- An exempted product is taxed at 0%.
- A rule change is live in < 5 seconds.
- A calculation returns the rules used in `snapshot`.
- A jurisdiction's rounding rule is honored.

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

