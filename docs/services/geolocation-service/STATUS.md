# geolocation-service — Status Snapshot

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
> | Documentation completeness | filesystem scan (`docs/services/geolocation-service/`) |
> | Contract snapshot | [`INTEGRATION.md`](./INTEGRATION.md) + [`docs/SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md) |
> | Security / RBAC | [`TECH.md`](./TECH.md) §10 + [`docs/services/RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §6.2a |
> | Plan snapshot | [`PLAN.md`](./PLAN.md) |

## 1. Identity

| Field | Value |
|---|---|
| Service name (kebab-case) | `geolocation-service` |
| Bounded context | **Bounded Context**: *Geocoding / ETA / routing adapter*. |
| Domain | Platform Foundation |
| Tier (deployment) | 0 (position 6 of 21; `DEPLOYMENT_ORDER.md` §2) |
| Criticality / SLO | T1 (99.95%) |
| Owner team | — |

## 2. Tech profile

| Field | Value | Source |
|---|---|---|
| Language | — | `TECH.md` §1 |
| Framework | — | `TECH.md` §1 |
| Profile | — | `RECOMMENDATIONS.md` §1 |
| DB schema | `geolocation` (per-service) | `services/README.md` env-var table |
| Cache | Redis — geocode+place cache | `TECH.md` §4 |
| HPA signal | CPU 70%, 2–6, p99 < 100ms | `TECH.md` §8 |
| Replicas (default) | — | `TECH.md` §8 |
| p99 latency target | — | `TECH.md` §8 |
| Image | `registry.trips-enjoy.com/geolocation-service:<sha>` | `README.md` §18 |
| Container port | 8080 | `TECH.md` §1 |
| Health endpoints | `/actuator/health/liveness`, `/actuator/health/readiness` | `TECH.md` §7 |
| `.env.example` | `apps/geolocation-service/.env.example` ✅ | filesystem |

## 3. Implementation lifecycle

> Source of truth: [`DEPLOYMENT_ORDER.md` §8.2](../../DEPLOYMENT_ORDER.md).
> Row copied verbatim from §8.2:

```
| 6 | `geolocation-service` | 0 | ✅ Graduated | 54 Go sources, multi-provider chain resolver, `sony/gobreaker` keyed by vendor, per-vendor token bucket, HMAC cache purge, 11 schema migrations | `go build` + `go vet` + `gofmt` + `go test` all green |
```

| Field | Value |
|---|---|
| Status | ✅ Graduated |
| `apps/geolocation-service/` Dockerfile | ✅ present |
| `apps/geolocation-service/k8s/` (flat kustomize overlays) | ✅ present |
| `apps/geolocation-service/monitoring/` (ServiceMonitor + PrometheusRule) | ✅ present |
| Local test suite | (see §8.2 row above) |
| Implementation memory | see `uber-geolocation-service-implementation-*.md` in project memory index |

## 4. Documentation completeness

| File | Required? | Present? | Last updated |
|---|---|---|---|
| `README.md` | ✅ mandatory | ✅ | 2026-08-14 |
| `BRD.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `SRS.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `ERD.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `INTEGRATION.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `WORKFLOWS.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `TECH.md` | ✅ mandatory | ✅ | 2026-08-12 |
| `PLAN.md` | ✅ mandatory | ✅ | 2026-08-14 |
| `SKELETON.go.mod` | ✅ mandatory | ✅ | 2026-08-07 |
| `STATUS.md` (this file) | ✅ mandatory (new) | ✅ | 2026-08-14 |

## 5. Contract snapshot

> Sources: [`INTEGRATION.md`](./INTEGRATION.md) §1–4 and
> [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md).

| Field | Count / Value |
|---|---|
| Inbound REST APIs | 6 (full contract in INTEGRATION.md §1) |
| Outbound REST APIs | 0 (INTEGRATION.md §2) |
| Produced events |  (INTEGRATION.md §3) |
| Consumed events |  (INTEGRATION.md §4) |
| Sync deps | see `SERVICE_INTEGRATION_MATRIX.md` row |
| Workflows participated | see `services/README.md` "By workflow participation" |

## 6. Security / RBAC

| Field | Value |
|---|---|
| AuthN | Bearer JWT (Keycloak) per `TECH.md` §6 |
| AuthZ | RBAC; admin role `geolocation-service.admin` per `TECH.md` §10 |
| SUPER_ADMIN preset | ✅ member of the 22-role preset (`platform.super_admin` + 21 × `<service>.admin`) per `services/RECOMMENDATIONS.md` §6.2a |
| Time-bounded alias | `platform-internal` realm `service-claims` scope mappers (per identity-service per-service claim contract); `geolocation-service.scopes` / `geolocation-service.level` / `geolocation-service.tenant` claims available |

## 7. Plan snapshot

> Source: [`PLAN.md`](./PLAN.md) (header lines 3–9 + phase blocks).

| Field | Value |
|---|---|
| Plan header | Domain: Platform Foundation / Tier: 1 / DB Schema: `geolocation` / Cache: Redis — geocode+place cache / HPA: CPU 70%, 2–6, p99 < 100ms |
| Phase 7.0 (cross-cutting) block | — |
| Phase 7.5 (Make-a-Deal kernel) block | — |
| Phase 7.6 (Conductor workers) block | — |
| Phase 7.7 (in-app chat) block | — |
| Plan task total | (count of `T-geolocation-service`-prefixed rows in `PLAN.md`) |
| Plan task status | pending: all · in_progress: 0 · done: 0 · blocked: 0 (PLAN.md task tables are all `pending` today) |

## 8. Cross-links

- **Sibling docs**: [README](./README.md) · [BRD](./BRD.md) · [SRS](./SRS.md) · [ERD](./ERD.md) · [INTEGRATION](./INTEGRATION.md) · [WORKFLOWS](./WORKFLOWS.md) · [TECH](./TECH.md) · [PLAN](./PLAN.md) · [SKELETON.go.mod](./SKELETON.go.mod)
- **Platform-wide**: [`services/README.md`](../README.md) · [`MICROSERVICES_MAP.md`](../../architecture/MICROSERVICES_MAP.md) · [`SERVICE_INTEGRATION_MATRIX.md`](../../SERVICE_INTEGRATION_MATRIX.md) · [`DEPLOYMENT_ORDER.md` §8](../../DEPLOYMENT_ORDER.md) · [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) §6.2a
- **Implementation memory** (graduates only): `uber-geolocation-service-implementation-<date>.md` (project memory index)
