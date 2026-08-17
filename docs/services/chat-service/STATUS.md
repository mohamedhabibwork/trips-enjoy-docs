# chat-service — Status Snapshot

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
> | Documentation completeness | filesystem scan (`docs/services/chat-service/`) |
> | Contract snapshot | [`INTEGRATION.md`](./INTEGRATION.md) + [`docs/SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md) |
> | Security / RBAC | [`TECH.md`](./TECH.md) §10 + [`docs/services/RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §6.2a |
> | Plan snapshot | [`PLAN.md`](./PLAN.md) |

## 1. Identity

| Field | Value |
|---|---|
| Service name (kebab-case) | `chat-service` |
| Bounded context | **Chat Thread (per service context)**. |
| Domain | — |
| Tier (deployment) | 3 (position 21 of 21; `DEPLOYMENT_ORDER.md` §2) |
| Criticality / SLO | — |
| Owner team | — |

## 2. Tech profile

| Field | Value | Source |
|---|---|---|
| Language | — | `TECH.md` §1 |
| Framework | — | `TECH.md` §1 |
| Profile | — | `RECOMMENDATIONS.md` §1 |
| DB schema | — (per-service) | `services/README.md` env-var table |
| Cache | — | `TECH.md` §4 |
| HPA signal | — | `TECH.md` §8 |
| Replicas (default) | — | `TECH.md` §8 |
| p99 latency target | — | `TECH.md` §8 |
| Image | `registry.trips-enjoy.com/chat-service:<sha>` | `README.md` §18 |
| Container port | 8080 | `TECH.md` §1 |
| Health endpoints | `/actuator/health/liveness`, `/actuator/health/readiness` | `TECH.md` §7 |
| `.env.example` | `apps/chat-service/.env.example` ✅ | filesystem |

## 3. Implementation lifecycle

> Source of truth: [`DEPLOYMENT_ORDER.md` §8.2](../../DEPLOYMENT_ORDER.md).
> Row copied verbatim from §8.2:

```
| 21 | `chat-service` | 3 | ⏳ Stub | 2 Go files (chat-service stub) | — |
```

| Field | Value |
|---|---|
| Status | ⏳ Stub |
| `apps/chat-service/` Dockerfile | — |
| `apps/chat-service/k8s/` (flat kustomize overlays) | — |
| `apps/chat-service/monitoring/` (ServiceMonitor + PrometheusRule) | — |
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
| `TECH.md` | ✅ mandatory | ✅ | 2026-08-14 |
| `PLAN.md` | ✅ mandatory | ✅ | 2026-08-14 |
| `SKELETON.go.mod` | ✅ mandatory | ✅ | 2026-08-12 |
| `STATUS.md` (this file) | ✅ mandatory (new) | ✅ | 2026-08-14 |

## 5. Contract snapshot

> Sources: [`INTEGRATION.md`](./INTEGRATION.md) §1–4 and
> [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md).

| Field | Count / Value |
|---|---|
| Inbound REST APIs | 13 (full contract in INTEGRATION.md §1) |
| Outbound REST APIs | 5 (INTEGRATION.md §2) |
| Produced events | 9 (INTEGRATION.md §3) |
| Consumed events | 15 (INTEGRATION.md §4) |
| Sync deps | see `SERVICE_INTEGRATION_MATRIX.md` row |
| Workflows participated | see `services/README.md` "By workflow participation" |

## 6. Security / RBAC

| Field | Value |
|---|---|
| AuthN | Bearer JWT (Keycloak) per `TECH.md` §6 |
| AuthZ | RBAC; admin role `chat-service.admin` per `TECH.md` §10 |
| SUPER_ADMIN preset | ✅ member of the 22-role preset (`platform.super_admin` + 21 × `<service>.admin`) per `services/RECOMMENDATIONS.md` §6.2a |
| Time-bounded alias | `platform-internal` realm `service-claims` scope mappers (per identity-service per-service claim contract); `chat-service.scopes` / `chat-service.level` / `chat-service.tenant` claims available |

## 7. Plan snapshot

> Source: [`PLAN.md`](./PLAN.md) (header lines 3–9 + phase blocks).

| Field | Value |
|---|---|
| Plan header | Domain: — / Tier: — / DB Schema: — / Cache: — / HPA: — |
| Phase 7.0 (cross-cutting) block | — |
| Phase 7.5 (Make-a-Deal kernel) block | — |
| Phase 7.6 (Conductor workers) block | — |
| Phase 7.7 (in-app chat) block | ✅ present |
| Plan task total | (count of `T-chat-service`-prefixed rows in `PLAN.md`) |
| Plan task status | pending: all · in_progress: 0 · done: 0 · blocked: 0 (PLAN.md task tables are all `pending` today) |

## 8. Cross-links

- **Sibling docs**: [README](./README.md) · [BRD](./BRD.md) · [SRS](./SRS.md) · [ERD](./ERD.md) · [INTEGRATION](./INTEGRATION.md) · [WORKFLOWS](./WORKFLOWS.md) · [TECH](./TECH.md) · [PLAN](./PLAN.md) · [SKELETON.go.mod](./SKELETON.go.mod)
- **Platform-wide**: [`services/README.md`](../README.md) · [`MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md) · [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md) · [`DEPLOYMENT_ORDER.md` §8](../../DEPLOYMENT_ORDER.md) · [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §6.2a
- **Implementation memory** (graduates only): `uber-chat-service-implementation-<date>.md` (project memory index)
