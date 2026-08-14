# payment-service — Status Snapshot

> **Composition page.** This file is a reader-rendered
> composition of fields from the canonical sources below.
> When any source changes, regenerate this file (see
> [`PLAN_INDEX.md`](../../PLAN_INDEX.md) "STATUS.md
> composition contract" for the contract and the doc-QA
> invariants).
>
> **Canonical sources for each field** (in order of
> preference; never duplicate the value — link to it):
>
> | Field group | Source of truth |
> |---|---|
> | Identity | [`docs/services/README.md`](../README.md) + [`README.md`](./README.md) §1–2 |
> | Tech profile | [`TECH.md`](./TECH.md) + [`docs/services/RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §2 |
> | Implementation lifecycle | [`docs/DEPLOYMENT_ORDER.md` §8.2](../../DEPLOYMENT_ORDER.md) |
> | Documentation completeness | filesystem scan (`docs/services/payment-service/`) |
> | Contract snapshot | [`INTEGRATION.md`](./INTEGRATION.md) + [`docs/SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md) |
> | Security / RBAC | [`TECH.md`](./TECH.md) §10 + [`docs/services/RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §6.2a |
> | Plan snapshot | [`PLAN.md`](./PLAN.md) |

## 1. Identity

| Field | Value |
|---|---|
| Service name (kebab-case) | `payment-service` |
| Bounded context | **Payment Gateway Integration**. |
| Domain | Financial Core |
| Tier (deployment) | 1 (position 16 of 21; `DEPLOYMENT_ORDER.md` §2) |
| Criticality / SLO | T0 (99.99%) |
| Owner team | — |

## 2. Tech profile

| Field | Value | Source |
|---|---|---|
| Language | — | `TECH.md` §1 |
| Framework | — | `TECH.md` §1 |
| Profile | — | `RECOMMENDATIONS.md` §1 |
| DB schema | `payment` (per-service) | `services/README.md` env-var table |
| Cache | Redis — idempotency + risk throttle | `TECH.md` §4 |
| HPA signal | CPU 70%, 3–8, p99 < 200ms | `TECH.md` §8 |
| Replicas (default) | — | `TECH.md` §8 |
| p99 latency target | — | `TECH.md` §8 |
| Image | `registry.trips-enjoy.com/payment-service:<sha>` | `README.md` §18 |
| Container port | 8080 | `TECH.md` §1 |
| Health endpoints | `/actuator/health/liveness`, `/actuator/health/readiness` | `TECH.md` §7 |
| `.env.example` | `apps/payment-service/.env.example` ✅ | filesystem |

## 3. Implementation lifecycle

> Source of truth: [`DEPLOYMENT_ORDER.md` §8.2](../../DEPLOYMENT_ORDER.md).
> Row copied verbatim from §8.2:

```
| 16 | `payment-service` | 1 | ⏳ Stub | 4 starter Kotlin files only | — |
```

| Field | Value |
|---|---|
| Status | ⏳ Stub |
| `apps/payment-service/` Dockerfile | — |
| `apps/payment-service/k8s/` (flat kustomize overlays) | — |
| `apps/payment-service/monitoring/` (ServiceMonitor + PrometheusRule) | — |
| Local test suite | (see §8.2 row above) |
| Implementation memory | — |

## 4. Documentation completeness

| File | Required? | Present? | Last updated |
|---|---|---|---|
| `README.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `BRD.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `SRS.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `ERD.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `INTEGRATION.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `WORKFLOWS.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `TECH.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `PLAN.md` | ✅ mandatory | ✅ | 2026-08-14 |
| `SKELETON.gradle.kts` | ✅ mandatory | ✅ | 2026-08-07 |
| `STATUS.md` (this file) | ✅ mandatory (new) | ✅ | 2026-08-14 |

## 5. Contract snapshot

> Sources: [`INTEGRATION.md`](./INTEGRATION.md) §1–4 and
> [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md).

| Field | Count / Value |
|---|---|
| Inbound REST APIs | 13 (full contract in INTEGRATION.md §1) |
| Outbound REST APIs | 3 (INTEGRATION.md §2) |
| Produced events | 16 (INTEGRATION.md §3) |
| Consumed events | 5 (INTEGRATION.md §4) |
| Sync deps | see `SERVICE_INTEGRATION_MATRIX.md` row |
| Workflows participated | see `services/README.md` "By workflow participation" |

## 6. Security / RBAC

| Field | Value |
|---|---|
| AuthN | Bearer JWT (Keycloak) per `TECH.md` §6 |
| AuthZ | RBAC; admin role `payment-service.admin` per `TECH.md` §10 |
| SUPER_ADMIN preset | ✅ member of the 22-role preset (`platform.super_admin` + 21 × `<service>.admin`) per `services/RECOMMENDATIONS.md` §6.2a |
| Time-bounded alias | `platform-internal` realm `service-claims` scope mappers (per identity-service per-service claim contract); `payment-service.scopes` / `payment-service.level` / `payment-service.tenant` claims available |

## 7. Plan snapshot

> Source: [`PLAN.md`](./PLAN.md) (header lines 3–9 + phase blocks).

| Field | Value |
|---|---|
| Plan header | Domain: Financial Core / Tier: 3 / DB Schema: `payment` / Cache: Redis — idempotency + risk throttle / HPA: CPU 70%, 3–8, p99 < 200ms |
| Phase 7.0 (cross-cutting) block | ✅ present |
| Phase 7.5 (Make-a-Deal kernel) block | — |
| Phase 7.6 (Conductor workers) block | ✅ present |
| Phase 7.7 (in-app chat) block | — |
| Plan task total | (count of `T-payment-service`-prefixed rows in `PLAN.md`) |
| Plan task status | pending: all · in_progress: 0 · done: 0 · blocked: 0 (PLAN.md task tables are all `pending` today) |

## 8. Cross-links

- **Sibling docs**: [README](./README.md) · [BRD](./BRD.md) · [SRS](./SRS.md) · [ERD](./ERD.md) · [INTEGRATION](./INTEGRATION.md) · [WORKFLOWS](./WORKFLOWS.md) · [TECH](./TECH.md) · [PLAN](./PLAN.md) · [SKELETON.gradle.kts](./SKELETON.gradle.kts)
- **Platform-wide**: [`services/README.md`](../README.md) · [`MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md) · [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md) · [`DEPLOYMENT_ORDER.md` §8](../../DEPLOYMENT_ORDER.md) · [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §6.2a
- **Implementation memory** (graduates only): `uber-payment-service-implementation-<date>.md` (project memory index)
