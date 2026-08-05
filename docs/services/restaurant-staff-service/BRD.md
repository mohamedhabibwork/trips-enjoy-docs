# restaurant-staff-service — Business Requirements Document

## 1. Document Purpose

This BRD defines the business capabilities, requirements, and rules
for the `restaurant-staff-service` — the canonical owner of
**restaurant staff assignments** (the users other than the merchant
owner who can operate a specific restaurant or branch). It is read
by:

- Product managers scoping staff onboarding.
- Engineering leads planning the service's roadmap.
- Restaurant operators when designing the operator console.
- Trust & Safety teams when designing deactivation flows.

It informs decisions on staff invitations, role assignments, device
allow-listing, and deactivation.

## 2. Business Context

A **restaurant staff member** is a user — distinct from the
merchant owner — who has been authorized to perform specific
operations on a specific restaurant or branch. Examples:

- A **manager** who can change hours and accept / reject orders.
- A **cashier** who can mark an order ready.
- A **kitchen** user who can mark an order preparing / ready.
- A **dispatcher** who can view the order queue.

Staff credentials live in Keycloak; this service holds the
**business assignment** (which staff can do what at which
restaurant or branch). Without this service, the platform could
not enforce granular roles at the operator console or POS
device, and could not safely deactivate a user without revoking
their Keycloak identity.

## 3. Objectives

| ID | Objective | Metric |
|----|-----------|--------|
| BR--001 | Allow a merchant owner to invite a staff member in < 2 minutes (operator time) | `staff_invitation_seconds` (P90) < 120 s |
| BR--002 | Activate a staff member within 5 minutes of accepting the invitation | `staff_activation_seconds` (P95) < 300 s |
| BR--003 | Reflect role changes in the operator console within 30 s | `role_propagation_seconds` (P95) < 30 s |
| BR--004 | Reflect deactivation across all services within 60 s | `deactivation_propagation_seconds` (P95) < 60 s |
| BR--005 | Ensure 100% of state changes are captured in the audit log | `audit_completeness` = 1.00 |
| BR--006 | Enforce RBAC consistently across all consumer services | `rbac_consistency_rate` = 1.00 |

## 4. Stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Merchant Owner | Inviter | clean onboarding, low friction |
| Restaurant Manager (staff) | First-line admin | can invite peers (within policy) |
| Restaurant Staff (cashier, kitchen, dispatcher) | Operator | can self-register devices |
| Platform Admin | Reviewer | can deactivate any staff |
| Platform Trust & Safety | Quality | ability to deactivate fast |
| Operator Console (system) | Consumer | reads RBAC on every action |
| POS Device (system) | Consumer | reads RBAC on every request |

## 5. Actors / Personas

- **Merchant Owner**: invites a staff member by email; assigns
  one or more roles at a specific restaurant or branch.
- **Restaurant Manager**: a staff member with role `manager`;
  can invite peers and assign roles within the same restaurant
  (subject to policy).
- **Restaurant Staff (cashier, kitchen, dispatcher)**: the
  invited user; signs up via Keycloak; activates their staff
  record; can self-register a POS device.
- **Platform Admin**: can deactivate any staff member.

## 6. Business Capabilities

- **Staff invitation**: create an invitation token; send by
  email.
- **Staff activation**: the invitee signs up via Keycloak,
  presents the token, and the staff record is created.
- **Role assignment**: assign one or more roles per restaurant
  or per branch.
- **Device allow-list**: a staff member can register up to N
  devices (POS tablets, etc.); the device ID is checked on every
  request.
- **Deactivation / reactivation**: the owner or admin can
  deactivate; the staff member's permissions are revoked; later
  reactivated.
- **Cascade handling**: parent restaurant suspension /
  closure → cascade deactivation; user suspension → cascade
  deactivation.

## 7. Business Requirements

| ID | Requirement | Priority | Source |
|----|-------------|----------|--------|
| BR--010 | The service MUST allow a merchant owner to invite a staff member by email | MUST | Product |
| BR--011 | The service MUST issue an invitation token with a configurable TTL | MUST | Product |
| BR--012 | The service MUST support activation via the invitation token | MUST | Product |
| BR--013 | The service MUST support role assignment per restaurant or per branch | MUST | RBAC |
| BR--014 | The service MUST support a device allow-list per staff member | MUST | Security |
| BR--015 | The service MUST support deactivation by the owner or admin with a reason code | MUST | Trust & Safety |
| BR--016 | The service MUST support reactivation | MUST | Product |
| BR--017 | The service MUST cascade parent restaurant suspension to deactivation of restaurant-scoped staff | MUST | Trust & Safety |
| BR--018 | The service MUST cascade user suspension / disablement to deactivation | MUST | Trust & Safety |
| BR--019 | The service MUST emit `staff.*.v1` events for every state change | MUST | Event architecture |
| BR--020 | The service MUST expose a fast RBAC check for the operator console and POS devices | MUST | Latency |
| BR--021 | The service MUST soft-delete (deactivate) staff, never hard delete, to preserve audit | MUST | Retention |
| BR--022 | The service MUST allow re-submission of an invitation if it expires | SHOULD | Product |

## 8. Business Rules

| ID | Rule | Notes |
|----|------|-------|
| BR--030 | A staff invitation requires: email, role(s), scope (restaurant or branch). | enforced server-side |
| BR--031 | An invitation token has a TTL (default 72 h). | configurable |
| BR--032 | A staff record is created on activation; the `kc_sub` is captured and cannot change. | immutable |
| BR--033 | Roles are drawn from `staff.roles.list`; multiple roles per staff are allowed. | enforced |
| BR--034 | Scope: `restaurant` (all branches) or `branch` (single branch). | enforced |
| BR--035 | A staff member can have at most N devices (default 3). | enforced |
| BR--036 | Deactivation requires a reason code from the platform enum. | enforced |
| BR--037 | A deactivated staff member cannot log in to the operator console or POS. | enforced at gateway |
| BR--038 | Reactivation restores the staff member to `active`; devices and roles are unchanged. | state transition |
| BR--039 | Cascade deactivation records the cause (`restaurant_suspended`, `user_suspended`, etc.). | audit |
| BR--040 | The merchant owner cannot deactivate themselves. | enforced |

## 9. Assumptions

- The merchant owner has a verified Keycloak identity
  (`merchant_owner` role).
- The invited email is real; the invitee has access to it.
- The invitee can sign up via Keycloak (or already has an
  account).
- Keycloak subject verification is available via
  `identity-service`.
- The operator console and POS devices include a `kc_sub` and
  `device_id` on every request.

## 10. Constraints

- The service is the source of truth for staff business
  assignments only. It MUST NOT store Keycloak credentials or
  POS device state.
- The service MUST be deployable independently of
  `identity-service`, `restaurant-service`, and
  `branch-service`.
- The service MUST remain within the platform's PCI scope
  (SAQ-A).
- All admin actions are subject to HMAC-SHA256 request signing
  and break-glass co-signature per
  [`SECURITY_ARCHITECTURE.md`](../../architecture/SECURITY_ARCHITECTURE.md).

## 11. Dependencies

| Dependency | Type | Notes |
|------------|------|-------|
| `identity-service` | service | Keycloak subject verification; user suspension cascade |
| `restaurant-service` | service | parent; cascade events |
| `branch-service` | service | parent (when scope is branch) |
| `configuration-service` | service | role enums; TTL; device limit |
| `notification-service` | service | send invitation / deactivation |
| `restaurant-order-mgmt-service` | service | RBAC consumer for operator console |
| `admin-service` | service | admin console |
| `audit-service` | service | audit events |
| Vault | infra | secrets |

## 12. Business Workflows

- **Staff Invitation and Activation**: invite, accept, activate.
- **Role Change**: owner / manager updates roles.
- **Device Registration**: staff self-registers a POS device.
- **Deactivation**: owner / admin / cascade deactivates.
- **Reactivation**: re-enable a deactivated staff member.
- **Cascade Deactivation**: parent restaurant suspended / closed
  → staff deactivated; user suspended → staff deactivated.

(Detailed sequences in `WORKFLOWS.md`.)

## 13. Exception Workflows

- **Invitation expired**: the invitee is shown an error and
  asked to request a new invitation.
- **Invitation token reuse**: 422 `INVITATION_ALREADY_ACCEPTED`.
- **Device limit exceeded**: 422 `DEVICE_LIMIT_EXCEEDED`.
- **Cascade conflict**: if a staff member is scoped to multiple
  restaurants, only the cascade-specific one is deactivated;
  the staff record itself remains active.

## 14. Success Criteria

- 100% of state changes are emitted as events.
- 100% of cascade deactivations reach downstream services within
  60 s.
- P90 invitation time < 2 min operator time.

## 15. KPIs

| KPI | Target | Measurement |
|-----|--------|-------------|
| Invitation time (P90) | ≤ 2 min | operator UI timing |
| Activation time (P95) | ≤ 5 min | invitation accepted → staff record |
| Role propagation (P95) | ≤ 30 s | `staff.role_changed.v1` → operator console cache |
| Deactivation propagation (P95) | ≤ 60 s | synthetic probe |
| RBAC check P99 | < 30 ms (cache hit) | `staff_rbac_check_seconds` |
| Cache hit rate | ≥ 90% | `staff_rbac_check_total{cache_hit}` |

## 16. Acceptance Criteria

- AC-1: A merchant owner can invite and activate a staff
  member in < 7 min.
- AC-2: A role change is reflected in the operator console
  within 30 s.
- AC-3: A deactivated staff member cannot perform any
  RBAC-protected action.
- AC-4: A suspended restaurant's restaurant-scoped staff are
  all deactivated within 60 s.
- AC-5: A user-suspended identity's staff records are all
  deactivated within 60 s.
- AC-6: All admin actions are recorded with reason and actor.
- AC-7: The service exposes a fast RBAC check with P99 < 30 ms.
- AC-8: The service meets its 99.9% SLO.
- AC-9: All state changes are emitted as events.
- AC-10: The service stores no Keycloak credentials.

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

