# Documentation Gap Report — Consolidated

**Generated**: 2026-08-12
**Scope**: docs/ tree, 249 markdown files, 21 services
**Source audits**:
- `.omo/plans/audit-services.md` — per-service structure + backrefs (262 lines)
- `.omo/plans/audit-links.md` — cross-doc link integrity (430 lines)
- `.omo/plans/audit-completeness.md` — content/TBD/stub audit (321 lines)

---

## Headline numbers

| Dimension | Count |
|-----------|------:|
| Markdown files in scope | 249 |
| Services documented (README/BRD/SRS/ERD/INTEGRATION/WORKFLOWS/TECH/PLAN) | 21 / 21 |
| Architecture docs | 21 |
| Workflow docs | 9 |
| Shared docs | 15 |
| Master index docs | 8 |
| ADRs | 20 (ADR-0001 through ADR-0020) |
| **Broken markdown links** | **320** (191 in services + 129 in master/architecture) |
| **Real placeholder/TBD markers in prose** | **0** |
| **Truly empty sections** | **2** (1 is a duplicate-heading bug) |
| **Duplicate-heading bugs** | **4** |
| Services missing `SKELETON.*` | 1 (`chat-service`) |
| CHANGELOG.md files anywhere | 0 |
| **Service count discrepancy** | brief said 22, actual is **21** |

---

## P0 — Critical (real bugs)

### P0-1: Duplicate `## 7. Correlation IDs` heading
- **File**: `docs/services/payment-service/INTEGRATION.md` lines 676 + 678
- **Likely cause**: merge artifact from 2026-08-12 polymorphic-request_id refactor
- **Fix**: dedupe, keep the more complete version (line 678 has 14 words of body; line 676 is empty)

### P0-2: `chat-service` missing from 4 master indexes
- **Files**: `docs/MASTER_PLAN.md`, `docs/MASTER_SERVICE_PLAN.md`, `docs/IMPLEMENTATION_PHASES.md`, `docs/SERVICE_INTEGRATION_MATRIX.md`
- **Status**: chat-service appears ONLY in `MASTER_PLAN_SUMMARY.md` and `PLAN_INDEX.md` (the newer docs)
- **Fix**: add chat-service rows consistent with the 20 other services

### P0-3: ADR-0020 missing from `ADR_INDEX.md`
- **File**: `docs/architecture/adrs/0020-polymorphic-request-id.md` exists
- **Issue**: not indexed
- **Fix**: add the ADR-0020 row to `ADR_INDEX.md`

### P0-4: Filename typo
- **File**: `docs/PLAN_INDEX.md:324` references `architecture/adrs/0018-conductor-workflow-engine.md`
- **Actual**: `architecture/adrs/0018-workflow-engine-conductor.md`
- **Fix**: correct the filename in the link

---

## P1 — High (structural gaps)

### P1-1: Wrong-depth relative paths in 320 links

Most broken links are path-depth arithmetic errors:

| Pattern | Occurrences | Fix |
|---------|------------:|----|
| `../shared/...` from `services/<svc>/INTEGRATION.md` (should be `../../shared/...`) | ~178 | prepend `..` |
| `../MASTER_TASK.md` from `services/<svc>/INTEGRATION.md` | ~31 | prepend `..` |
| `../architecture/adrs/...` from `services/<svc>/INTEGRATION.md` | ~33 | prepend `..` |
| `../MIGRATION_HUB.md` from `architecture/adrs/0016-*.md`, `0017-*.md` | 4 | prepend `..` |
| `../CONVENTIONS.md` from `architecture/adrs/0019-*.md` | 2 | prepend `..` |
| `../../...` from `MASTER_TASK.md` (file is at `docs/` not nested) | ~46 | drop one `..` |
| `../../services/...` from `shared/TYPE_CATALOG.md` (file is at `docs/shared/`) | ~44 | drop one `..` |
| `../...` from `architecture/adrs/0020-*.md` (file is at `docs/architecture/adrs/`) | 8 | prepend `..` |
| `../shared/...` from `shared/CONDUCTOR_WORKFLOWS.md` | 3 | drop one `..` |
| `/services/...` (absolute-root) in `driver-service/INTEGRATION.md` | 6 | replace `/services/` → `../../services/` |

These are mechanical fixes — script-driven.

### P1-2: Non-existent file references

| Bad target | Referenced in | Action |
|------------|---------------|--------|
| `notification-service/WHATSAPP_PROVIDER_CONTRACT.md` | `WHATSAPP_TEMPLATES.md`, `INTEGRATION.md`, `PLAN.md` (4×) | create the file OR remove references |
| `shared/NOTIFICATION_TEMPLATES.md` | `notification-service/TECH.md:208` | create the file OR remove the link |

### P1-3: 19 of 21 service READMEs omit `PLAN.md` from Sibling docs

Only `chat-service` and `notification-service` include `PLAN.md` in the canonical Sibling docs list. The other 19 list only 6 of the 8 canonical files.

**Fix**: append `- [`PLAN.md`](./PLAN.md) — implementation tasks, phases, dependencies` to each.

### P1-4: Architecture docs missing canonical backrefs (14 / 17 non-canonical)

Architecture docs SHOULD reference SYSTEM_OVERVIEW, MICROSERVICES_MAP, DATA_OWNERSHIP, EVENT_ARCHITECTURE. 14 lack one or more of these.

**Fix**: add a "Related Architecture Docs" footer to each.

### P1-5: All 9 workflow docs lack `SERVICE_INTEGRATION_MATRIX.md` backref

None of `RIDE_WORKFLOWS.md`, `FOOD_ORDER_WORKFLOWS.md`, `COURIER_WORKFLOWS.md`, `DRIVER_WORKFLOWS.md`, `MERCHANT_WORKFLOWS.md`, `PAYMENT_WORKFLOWS.md`, `REFUND_WORKFLOWS.md`, `SAFETY_WORKFLOWS.md`, `ACCOUNTING_WORKFLOWS.md` link to the integration matrix.

**Fix**: add "Related docs" footer linking to `../SERVICE_INTEGRATION_MATRIX.md`.

### P1-6: 10 of 12 shared docs lack CONVENTIONS / PLATFORM_BASELINE backref

**Fix**: add "Related docs" footer to each shared doc.

---

## P2 — Medium (content polish)

### P2-1: 19 of 21 ERDs don't reference architecture docs

Only `payment-service/ERD.md` (→ SERVICE_ISOLATION) and `geolocation-service/ERD.md` (→ DATA_OWNERSHIP) have architecture backrefs. The other 19 ERDs are isolated from the architecture tree.

**Fix**: add a "Related docs" footer to each ERD.

### P2-2: 6 TECH.md sections with em-dash-only content

`## 5. External integrations` and `## 4. Cache` sections in admin, audit, configuration, courier, customer, restaurant services contain only `—` (em-dash).

**Fix**: replace `—` with `None` or remove the section if genuinely empty (per template convention).

### P2-3: `chat-service/PLAN.md` uses flat Sprint column instead of `### Phase N` headings

20 of 21 services use `### Phase N — ...` headings in their PLAN.md. `chat-service/PLAN.md` uses a flat Sprint column instead — structurally inconsistent.

**Fix**: optional — either add `### Phase N` headings to chat-service or document the deviation.

### P2-4: Duplicate `### 4.2 customer.suspended.v1` in admin-service/INTEGRATION.md

**Fix**: dedupe.

### P2-5: Duplicate `### 10.5 Admin enforcement` and `### 10.6 Local admin (dev only)` in admin-service/TECH.md

**Fix**: dedupe.

### P2-6: `chat-service` has no `SKELETON.*` (skeleton manifest)

All 20 other services have `SKELETON.gradle.kts` / `SKELETON.go.mod` / `SKELETON.pyproject.toml`. `chat-service` (Go + chi + WebSocket, per memory) lacks one.

**Fix**: create `SKELETON.go.mod`.

### P2-7: `### 11.2 See also` empty in `shared/TYPE_CATALOG.md`

**Fix**: populate the See also section.

### P2-8: 1 TBD in `notification-service/seeds/RENDERING_DEMO.md:198`

The only true TBD marker in the entire tree. Demo/seed doc — low impact.

---

## P3 — Strategic / convention (no action required today)

### P3-1: Zero CHANGELOG.md files in tree

The 2026-08-12 polymorphic-request_id refactor was applied inline (logged in `.omo/plans/polymorphic-request-id-refactor.md`). No per-service changelog audit trail.

**Decision needed**: adopt per-service `CHANGELOG.md` going forward, OR formalize `.omo/plans/*.md` as the canonical audit trail.

### P3-2: Service count discrepancy

Brief said 22; actual is 21. The 22nd was likely intended to be `chat-service` (added Phase 7.7) plus the original 20 from ADR-0017 = 21. Update memory + adjust wording in prompts.

### P3-3: 2 false-positive "broken links" (regex literals)

`restaurant-service/ERD.md` lines 74 + 228 contain `^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])?$` inside a Postgres CHECK constraint description — not actual markdown links.

**No action** (cosmetic).

---

## Out of scope (intentional)

- **HLD.md / LLD.md do not exist** — these will be GENERATED (not fixed) as part of this work cycle, after the 4th architecture-extraction audit completes.
- **notification-service extras** (`MESSAGE_HISTORY.md`, `TEMPLATE_HISTORY.md`, `WHATSAPP_TEMPLATES.md`, `seeds/`) are intentional, not drift.
- **payment-service `GATEWAYS.md`** is intentional, not drift.
- **Legit "N/A" / "—" terse answers** in template sections are correct per the doc contract.

---

## Work plan

This gap report drives the next 4 deliverables:

1. **Fix P0 critical bugs** (5 small edits)
2. **Fix P1 broken links** (~320 mechanical edits, script-driven)
3. **Wire P1 backrefs** (43 docs need footers added)
4. **Generate HLD.md + LLD.md** (single docs/architecture/HLD.md, single docs/architecture/LLD.md)
