# Trips Enjoy Documentation Full Upgrade — Design Specification

**Date:** 2026-11-11  
**Status:** Draft (Pending User Approval)  
**Scope:** Reusable MDX Component Kit, Enriched Delivery Tasks (EN + AR), Platform-Wide Mindmaps, End-to-End System Blueprint, Recommendations & Open-Decision Resolutions, and Automated Quality Verification.

---

## 1. Intent and Why

**Trips Enjoy** (`trip-docs`) publishes the canonical bilingual (`en` and `ar`) specification for a multi-role organized-trips marketplace on Fumadocs + TanStack Start. While the repository already links Business Requirements (`BRD`), Software Requirements (`SRS`), Data Model (`ERD`), User Flows (`UF`), System Workflows, Delivery Plan (`P1`–`P6`), Architecture, Design System, and AI/MCP endpoints, four major gaps keep it from serving as a complete, self-contained engineering and product playbook:

1. **Bare Delivery Tasks:** `content/docs/plan/p1..p6.mdx` (and `ar/plan/p1..p6.mdx`) render 213 functional tasks as thin 4-column table rows (`T-P1-AUTH-1 | FR-AUTH-01 | Must | UF-...`) and 18 platform tasks as 2-column rows with no task IDs, no descriptions, no inline acceptance criteria, no ERD table mappings, no business-rule context, and incomplete cross-pillar trace links.
2. **Zero Mindmaps:** `mermaid@12.3.0` is wired into `<Mermaid />`, yet no mindmaps exist anywhere in the documentation to give readers visual mental models of roles, domains, capabilities, phases, workflows, or quality attributes.
3. **No Unified End-to-End Lifecycle View:** Readers must jump across `brd`, `srs`, `erd`, `user-flows`, `workflows`, and `architecture` to piece together how a trip actually runs from tenant onboarding and supplier deals through booking, trip-day live operations, photo albums, and double-entry financial settlement.
4. **No Recommendations or Recommended Resolutions:** `brd/compliance.mdx` lists **32** `OD-*` decisions (`OD-01`..`OD-32`, 21 `Open` or `Proposed`, including two active spec conflicts in `OD-29` and `OD-32`) plus **15 inline `[OPEN]` markers** across `brd/`, `srs/`, `erd/`, and `design/` without concrete recommended resolutions, trade-offs, or engineering/operational guidance.
5. **Pre-existing Count & Link Drift:** `UF-SHR-17` brought the actual role-flow count to **106** while index pages and `landing.content.ts` still report `105` or `95`/`201`; `ar/workflows/coverage.mdx` is stale at `207/212` FRs (vs `213/213` in EN); and `srs/quality.mdx` has a malformed nested link (`[NFR-[DAT-04](...)](...)`).

The goal of this upgrade is to make every part of the documentation **richly detailed inline** (never link-only stubs) **and fully cross-linked** across every documentation pillar (`BRD`, `SRS`, `ERD`, `PLAN`, `WORKFLOW`, `USER FLOWS`, `ARCHITECTURE`, `DESIGN`), backed by reusable MDX components and verified across both English and Arabic.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
- **Reusable MDX Component Kit:** Ship typed, locale-aware (`en` / `ar` + RTL-safe) React/MDX components registered globally in `src/components/mdx/index.tsx` for cross-pillar trace bars, delivery tasks, recommendations/decision resolutions, end-to-end system stages, and mindmap blocks.
- **Full Inline Detail + Full Cross-Pillar Links:** Every task, end-to-end stage, and recommendation renders complete inline specifications (descriptions, shall-statements, acceptance checks, tables touched, rules, deliverables, rationale) **plus** clickable links to `BRD`, `SRS`, `ERD`, `PLAN`, `WORKFLOW`, `USER FLOWS`, `ARCHITECTURE`, and `DESIGN`.
- **Search, `.md`, `/llms-full.txt`, and MCP Indexing:** Pass substantive prose as MDX children (not opaque JSX prop-only blobs) so Fumadocs `structuredData()` and `getText('processed')` index all new content for `/api/search`, `/api/mcp`, `/{lang}/docs/*.md`, and `/llms-full.txt`.
- **Upgraded Deterministic Plan Generators (`EN` + `AR`):** Upgrade `scripts/generate-plan.ts` and `scripts/generate-plan-ar.ts` so all 213 functional tasks + 18 cross-cutting platform tasks (231 total per locale) generate rich `<TaskCard />` entries directly from the living English and Arabic specs without manual drift.
- **Visual Mindmaps Everywhere:** Add a dedicated Mindmap Atlas (`system/mindmaps.mdx`) plus domain-specific mindmaps on the overview pages of `brd`, `srs`, `erd`, `user-flows`, `workflows`, `plan`, `architecture`, `design`, and `recommendations` in both English and Arabic.
- **End-to-End System Hub (`system/`):** Add a dedicated bilingual hub tracing the complete 8-stage Trips Enjoy lifecycle end-to-end with sequence/flow diagrams, mindmaps, failure/retry paths, data mutations, and `<SystemStage />` blocks.
- **Recommendations & Recommended Resolutions Hub (`recommendations/`):** Provide concrete, MENA-benchmarked recommended resolutions for **all 32 `OD-*` decisions (`OD-01`..`OD-32`)** and **all 15 inline `[OPEN]` markers** across the repository, alongside architecture, security, MENA payments/ZATCA, offline sync, and rollout recommendations—without mutating decided product rules in the BRD as if already signed off (marking recommendations clearly as *Recommended Resolution*).
- **100% Bilingual Parity, Drift Repair & Zero Broken Links:** Repair pre-existing count/coverage/link drift (`106` role flows, `213/213` Arabic workflow coverage sync, malformed nested link in `srs/quality.mdx`), and ensure every new/updated English page has a matching Arabic page under `content/docs/ar/` with identical heading count/order so `scripts/link-ids-ar.ts` maps anchors cleanly, verified by an automated quality audit script (`scripts/verify-docs-quality.ts`), `bun run types:check`, `bun run lint`, and full prerender `bun run build`.

### Non-Goals
- Changing the underlying Trips Enjoy product scope (e.g., adding hotel/flight inventory or ML fraud scoring that BRD §3.2 marks out of scope).
- Converting the 231 tasks into 462 standalone MDX files.
- Silently overwriting `[OPEN]` or `Proposed` statuses in `brd/compliance.mdx` into `Decided` without stakeholder sign-off—recommendations must be explicitly labeled as recommended resolutions while preserving auditability of what is already `Decided` vs `Proposed` vs `Open`.

### Anti-Goals (Failure Modes Even If Technical Build Passes)
- **Link-only stubs:** Cards or tables that list IDs/links without explaining what gets built, why, which tables change, and how acceptance is verified.
- **Violating BRD §1 Document Rule:** Putting code schemas or technical implementation diagrams inside `content/docs/brd/*` (BRD §1 explicitly forbids code, schemas, or diagrams in code form inside BRD pages—Wait: note on BRD mindmap: keep `brd/` strictly prose/tables per BRD §1 rule, and host the BRD/Commercial Mindmap in `system/mindmaps.mdx` with cross-links!).
- **Breaking Arabic Anchor Parity:** Having mismatched heading counts between `content/docs/<page>.mdx` and `content/docs/ar/<page>.mdx`, which causes `scripts/link-ids-ar.ts` to skip anchor localization.
- **Broken Prerender Links or Mermaid Parse Failures:** Any Mermaid syntax error (such as unquoted parentheses in node labels or invalid mindmap indentation) or any 404/broken `#anchor` across `/en/` and `/ar/`.

---

## 3. Constraints

1. **Framework & Build:** TanStack Start + Fumadocs (`fumadocs-core@16.18`, `fumadocs-mdx@15.5`, `@fumadocs/base-ui@16.18`), React 19, Tailwind CSS v4, Mermaid v12.3, Bun runtime, `oxlint` + `tsc --noEmit` + full static prerendering (`vite build`).
2. **BRD §1 Rule:** `content/docs/brd/index.mdx` states: *"Rule for this document: No code, schemas or diagrams in code form. Those live in the SRS, the ERD and the architecture document."* We must honor this rule strictly: `brd/*` gets rich prose/table cross-links to `recommendations/` and `system/`, while visual mindmaps for business/commercial structure live in `system/mindmaps.mdx` and non-BRD section indexes.
3. **Positional Heading Parity (`link-ids-ar.ts`):** `scripts/link-ids-ar.ts` pairs headings positionally between `content/docs/<rel>.mdx` and `content/docs/ar/<rel>.mdx` using `github-slugger`. Every EN/AR pair must keep a 1-to-1 heading match.
4. **Mermaid Component Behavior:** `remarkMdxMermaid` transforms fenced ```` ```mermaid ```` blocks into `<Mermaid chart="..." />` at compile time. Any wrapper component (`<MindmapBlock />`) wraps MDX children containing standard ```` ```mermaid ```` fences so `remarkMdxMermaid` compiles them identically and markdown endpoints (`*.md`, `/llms-full.txt`) preserve the raw diagram fence cleanly!

---

## 4. Approaches Considered

| Approach | Summary | Trade-offs | Decision |
|---|---|---|---|
| **1. Component Kit + Upgraded Generators + Dedicated `system/` & `recommendations/` Hubs** | Global reusable MDX components (`TraceBar`, `TaskCard`, `RecommendationCard`, `SystemStage`, `MindmapBlock`), upgraded EN/AR plan generators emitting full task cards, two new bilingual top-level sections (`system/` and `recommendations/`), section mindmaps, and automated quality verification. | Slightly wider top-level navigation (12 sections instead of 10), in exchange for clean separation, BRD §1 compliance, zero generator drift, fast static prerendering, and first-class discoverability. | **Chosen** (selected by user) |
| **2. Embed Only Inside Existing Sections** | Same components and rich tasks, but cram end-to-end lifecycle into `architecture/` + `workflows/` and all recommendations into `brd/compliance.mdx`. | Keeps 10 sidebar items, but bloats existing pages, mixes technical recommendations into the business-only BRD, and buries key views. | Rejected |
| **3. Separate MDX File Per Task (460+ Files) + Hubs** | Standalone URL per task (`plan/p1/t-p1-auth-1.mdx` × 2 locales) plus new hubs. | Quadruples prerender build size (~600+ pages), fragments module epics across dozens of clicks, and pollutes search results. | Rejected |

---

## 5. Detailed Design

### 5.1 Reusable MDX Component Kit (`src/components/docs-kit/`)

All components are exported from `src/components/docs-kit/index.ts` and registered in `src/components/mdx/index.tsx` so any `.mdx` file can use them without per-file imports. All UI labels automatically switch between English and Arabic based on `useLocation().pathname.startsWith('/ar/')` (matching the pattern established in `src/components/req-meta.tsx`), using logical CSS properties (`ps-*`, `pe-*`, `ms-*`, `me-*`, `text-start`) for seamless RTL layout.

#### 1. `<TraceBar />` (`src/components/docs-kit/trace-bar.tsx`)
A structured, color-coded cross-pillar reference bar linking an item to every related pillar in the documentation set.
- **Props:**
  ```ts
  export type TraceLinkItem = {
    label: string;       // e.g. "BR-AUTH-01", "users", "UF-TRV-02", "T-P1-AUTH-1"
    href: string;        // locale-prefixed or auto-localized path + anchor
    detail?: string;     // optional short badge tooltip/note, e.g. "Must" or "R/W"
  };

  export interface TraceBarProps {
    brd?: TraceLinkItem[];          // Business requirements (BR-*), rules (BRL-*), decisions (OD-*)
    srs?: TraceLinkItem[];          // Functional (FR-*), quality (NFR-*), data (DAT-*), state machines
    erd?: TraceLinkItem[];          // ERD domains and exact table anchors
    plan?: TraceLinkItem[];         // Phase & delivery task anchors (T-P*-*)
    workflows?: TraceLinkItem[];    // System workflow pages & sections
    userFlows?: TraceLinkItem[];    // Role flows (UF-*)
    architecture?: TraceLinkItem[]; // Architecture sections
    design?: TraceLinkItem[];       // Design system sections
    recommendations?: TraceLinkItem[]; // Linked REC-* / OD-* resolutions
    compact?: boolean;
  }
  ```
- **Behavior:** Renders grouped pillar rows/pills (`BRD`, `SRS`, `ERD`, `PLAN`, `WORKFLOW`, `USER FLOW`, `ARCH`, `DESIGN`, `REC` — localized in Arabic as `الأعمال`, `البرمجيات`, `البيانات`, `الخطة`, `سير العمل`, `تدفق المستخدم`, `المعمارية`, `التصميم`, `التوصيات`). Uses `fumadocs-core/link` for client routing. Also supports auto-localizing `/en/docs/...` paths to `/ar/docs/...` via `arAnchors` if needed, though generators will emit exact locale-native URLs.

#### 2. `<TaskCard />` (`src/components/docs-kit/task-card.tsx`)
Replaces bare table rows on `plan/p1..p6.mdx` (and `ar/plan/p1..p6.mdx`).
- **Props:**
  ```ts
  export interface TaskCardProps {
    id: string;                     // e.g. "T-P1-AUTH-1" or "T-P1-PLAT-1"
    title: string;                  // Task title
    phase: 'P1' | 'P2' | 'P3' | 'P4' | 'P5' | 'P6';
    module: string;                 // e.g. "AUTH" or "PLAT"
    priority: 'Must' | 'Should' | 'Could';
    kind?: 'functional' | 'platform';
    surfaces?: string[];            // Affected apps/surfaces (e.g. ["Traveler App", "Org Dashboard", "API"])
    tables?: string[];              // Exact table names displayed in quick summary chip row
    trace: TraceBarProps;           // Complete cross-pillar links (BRD, SRS, ERD, Plan, Workflow, UF, Arch, Design, Rec)
    children: ReactNode;            // Full MDX body: Description, Specification, Acceptance Criteria, Deliverables, Rules/Decisions
  }
  ```
- **Anchor & Heading Integration:** Inside MDX, each task is preceded by an `### T-P1-AUTH-1 — Title` heading (or the heading is placed right above `<TaskCard />` in MDX) so Fumadocs Table-of-Contents (`TOC`), `structuredData()` search indexing, `github-slugger`, and `scripts/link-ids-ar.ts` index every task as a first-class deep-linkable section! *(Builder call: keeping `### T-P1-AUTH-1 — Title` as a standard Markdown heading immediately followed by `<TaskCard ...>` guarantees 100% compatibility with Fumadocs right-rail TOC, Orama search section anchors, `.md` export, and `link-ids-ar.ts` without custom remark AST plugins.)*

#### 3. `<RecommendationCard />` (`src/components/docs-kit/recommendation-card.tsx`)
Renders a decision resolution (`OD-*` / `[OPEN]`) or an architectural/operational/UX recommendation (`REC-*`).
- **Props:**
  ```ts
  export interface RecommendationCardProps {
    id: string;                     // e.g. "OD-05", "OD-10", "REC-ARCH-01"
    title: string;
    category: 'open-decision' | 'architecture' | 'security' | 'operations' | 'payments-tax' | 'ux-rtl';
    currentStatus: 'Open' | 'Proposed' | 'Decided — Implementation Guidance' | 'Strategic Advisory';
    targetPhase: string;            // e.g. "Before P2", "Before P5", "P1–P6"
    owner: string;                  // e.g. "Product & Finance", "Engineering"
    impact: 'Critical' | 'High' | 'Medium';
    recommendedResolution: string;  // Highlighted callout summary of the concrete recommendation
    trace: TraceBarProps;           // Full links to BRD, SRS, ERD, Plan tasks, Workflows, Arch, Design
    children: ReactNode;            // Full MDX rationale, implementation blueprint, trade-offs & rejected alternatives, validation gate
  }
  ```

#### 4. `<SystemStage />` (`src/components/docs-kit/system-stage.tsx`)
Renders one stage of the End-to-End System Lifecycle (`STAGE-01` through `STAGE-08`).
- **Props:**
  ```ts
  export interface SystemStageProps {
    stageNumber: number;            // 1..8
    id: string;                     // e.g. "STAGE-01"
    title: string;
    phaseIntroduced: string;        // e.g. "P1–P2"
    actors: string[];               // Roles involved
    stateMachines?: string[];       // Active state machines in this stage
    tablesWritten?: string[];       // Primary tables mutated
    eventsEmitted?: string[];       // Outbox / webhook / realtime events emitted
    trace: TraceBarProps;           // Full links across BRD, SRS, ERD, Plan, Workflows, UFs, Arch, Design
    children: ReactNode;            // Full MDX narrative: Happy path, Timers/SLAs, Failure & Retry recovery, Diagram
  }
  ```

#### 5. `<MindmapBlock />` (`src/components/docs-kit/mindmap-block.tsx`)
Wraps a ```` ```mermaid ```` `mindmap` (passed via `children` so `remarkMdxMermaid` compiles it into `<Mermaid />`) inside a framed figure with a title, caption, key takeaways, and a `<TraceBar />` jump-link footer so every branch of the mindmap has clickable navigation into the spec.
- **Props:**
  ```ts
  export interface MindmapBlockProps {
    title: string;
    caption: string;
    trace?: TraceBarProps;
    children: ReactNode;            // Contains the ```mermaid mindmap ... ``` block + optional key takeaways
  }
  ```

---

### 5.2 Upgraded Delivery Plan & Task Generators (`scripts/generate-plan.ts` & `scripts/generate-plan-ar.ts`)

Instead of thin 4-column tables, `scripts/generate-plan.ts` (for `en`) and `scripts/generate-plan-ar.ts` (for `ar`) will become full, deterministic, idempotent generators for `plan/p1..p6.mdx`, `plan/milestones.mdx`, `ar/plan/p1..p6.mdx`, and `ar/plan/milestones.mdx`.

#### Data Extracted & Synthesized Per Functional Task (`213` tasks):
1. **Identity & Phase:** `T-P{n}-{MOD}-{seq}` mapped from `FR-{MOD}-{nn}` → `BR-*` phase (with traceability fallback, preserving exact existing task IDs).
2. **Full SRS Specification (Inline, Not Link-Only):**
   - Full requirement prose (the verbatim `shall` specification from `srs/requirements/{mod}.mdx` in EN and `ar/srs/requirements/{mod}.mdx` in AR).
   - Full Given/When/Then **Acceptance Criteria** bullets parsed directly from the SRS requirement block.
3. **Implementation Scope & Deliverables:**
   - Derived deterministically from module + requirement semantics (API endpoints/guards, state-machine transitions if cited in `srs/state-machines.mdx`, scheduled jobs/timers if cited in `workflows/scheduled-jobs.mdx`, affected client surfaces from `design/index.mdx`, and NFR/Data rules).
4. **Exact Data Model Mapping (Domain + Table Level):**
   - Parses every `erd/domains/*.mdx` table heading (`## table_name`) and its FR citations/module mapping so each task links not only to the ERD domain page(s), **but also to the exact PostgreSQL tables** (e.g., `users`, `user_sessions`, `applications`, `bookings`, `ledger_entries`) plus `[ERD conventions](/en/docs/erd)` and `[ACL / RLS](/en/docs/erd/acl)`.
5. **Business Context & Governance:**
   - Inline summary of upstream `BR-*` statements, cited `BRL-*` business rules (parsed from `brd/requirements.mdx` §6 & §7), and any cited `OD-*` decisions (linked both to `brd/compliance#11-open-decisions` and to the concrete recommended resolution in `recommendations/open-decisions`).
6. **Complete `<TraceBar />` on Every Task:**
   - **BRD:** `BR-*`, `BRL-*`, `OD-*` (falling back to module BRD section anchor when an FR traces only via `BRL-*` or `§10.1`).
   - **SRS:** Exact `FR-*` anchor + linked state machine (`srs/state-machines`) + module quality/data rules (`srs/quality`, `srs/data`).
   - **ERD:** Exact domain page(s) + exact `#table_name` anchors + `erd/acl` where tenant/role scoped.
   - **Plan:** Phase milestone anchor (`plan/milestones`) + `plan/testing` verification layer.
   - **Workflows:** Covering `workflows/*` pages & anchors (or `workflows/index` engine contract when purely synchronous).
   - **User Flows:** Covering `UF-*` role flows (or module's primary role flow page).
   - **Architecture & Design:** Relevant `architecture/index#*` section (e.g., Data architecture, Integrations, Quality attributes) and `design/index#*` app surface (e.g., Traveler app, Organization dashboard, Admin console).
   - **Recommendations:** Linked `OD-*` / `REC-*` anchor in `recommendations/`.

#### Cross-Cutting Platform Tasks (`18` tasks: `T-P1-PLAT-1` .. `T-P6-PLAT-3`):
- Upgraded from 2-column one-liners into full `### T-P{n}-PLAT-{k}` + `<TaskCard kind="platform" />` blocks (in both EN and AR) with authored descriptions, deliverables, verification criteria, and full `<TraceBar />` links to `BRD`, `SRS` (`NFR-*` / `DAT-*`), `ERD`, `Workflows`, `Architecture`, `Design`, and `Recommendations`.

#### Phase Summary Table + Phase Mindmap:
- Each phase page (`p1.mdx`..`p6.mdx`) opens with:
  1. Theme, Definition of Done, task & priority breakdown (`Must`/`Should`/`Could` + `Platform`).
  2. A `<MindmapBlock />` visualizing that phase's modules, platform foundations, and milestone gate.
  3. A compact quick-jump summary index table linking to each `#t-p1-...` anchor on the page, followed by the full `<TaskCard />` sections per module epic, and closing with enriched **Decisions to Close** (showing status + recommended resolution link).

---

### 5.3 Mindmaps Strategy (`mermaid` `mindmap`)

Mermaid v12.3 supports `mindmap` diagrams natively. To prevent any Mermaid v12 `mindmap` syntax pitfalls:
- Use clean 2-space indentation, single root node `root(("Trips Enjoy ..."))`, and avoid raw parentheses `(` `)` or brackets `[` `]` inside plain text labels unless wrapped in shape delimiters or replaced with `—` / `:`.
- Every mindmap will be pre-validated with `mermaid` / syntax rules and rendered inside `<MindmapBlock />` with a `<TraceBar />` below it.

#### Mindmap Placement (Both `EN` and `AR`):
1. **Dedicated Mindmap Atlas (`content/docs/system/mindmaps.mdx` & `ar/system/mindmaps.mdx`):**
   - Ecosystem & 9 Stakeholder Roles Mindmap
   - Business & Commercial Model Mindmap (keeping `brd/*` 100% diagram-free per BRD §1!)
   - 28 SRS Functional Modules Capability Mindmap
   - 14 ERD Data Domains & Tenancy Mindmap
   - 10 System Workflows & Scheduled Jobs Mindmap
   - 6 Delivery Phases (`P1`–`P6`) Roadmap Mindmap
   - Quality, Security & Compliance (`NFR` + `PDPL`/`GDPR`/`ZATCA`) Mindmap
2. **Section Index Mindmaps:**
   - `srs/index.mdx` (+ AR): 28 Modules grouped by domain cluster
   - `erd/index.mdx` (+ AR): 14 Data Domains & Cross-Cutting Spine
   - `user-flows/index.mdx` (+ AR): 8 Role Flow Suites (106 flows)
   - `workflows/index.mdx` (+ AR): Engine Contract, State Machines & Job Cadences
   - `plan/index.mdx` (+ AR): P1→P6 Delivery Progression & Gate Dependencies
   - `plan/p1..p6.mdx` (+ AR): Per-phase scope mindmap (generated by `generate-plan.ts` / `generate-plan-ar.ts`)
   - `architecture/index.mdx` (+ AR): Topology, Data Stores, Integrations & NFR Enforcement
   - `design/index.mdx` (+ AR): Design Tokens, RTL Rules, Component Kit & 7 App Surfaces

---

### 5.4 End-to-End System Hub (`content/docs/system/` & `content/docs/ar/system/`)

A new top-level documentation folder `system/` (`meta.json`: `index`, `lifecycle`, `mindmaps`):

1. **`system/index.mdx` — End-to-End System Overview:**
   - Executive synthesis of how Trips Enjoy operates as one cohesive organism across 8 surfaces, 28 SRS modules, 14 ERD domains, 10 workflows, and 6 phases.
   - Master End-to-End Architecture & Data-Flow Diagram (`flowchart TB`) + Master Role-Interaction Sequence Diagram (`sequenceDiagram`).
   - Cross-Domain Traceability Matrix mapping every lifecycle stage to its `BRD`, `SRS`, `ERD`, `User Flows`, `Workflows`, `Plan Phases`, `Architecture`, `Design`, and `Recommendations`.
2. **`system/lifecycle.mdx` — Complete 8-Stage Operational Journey:**
   - Uses `<SystemStage />` for each stage from start to finish, with full inline specifications (actors, pre-conditions, step-by-step system + human choreography, DB writes, Redis/outbox/WebSocket activity, timers/SLAs, failure & compensation paths, sequence diagram, and `<TraceBar />`):
     - **Stage 1:** Identity, Verification, Multi-Role Accounts & Market Assignment (`AUTH`, `MKT`)
     - **Stage 2:** Organization & Supplier Applications, Admin Review, Lab Tests & Tenant Provisioning (`ONB`, `ORG`, `EMP`, `DRV`, `SUB`)
     - **Stage 3:** Fleet Configuration, Seat Layouts, Supplier Deal Negotiation & Staffing (`FLT`, `SEAT`, `NEG`, `STF`, `PHO`, `MOD`)
     - **Stage 4:** Trip Creation, Programs, Coupons, Pricing & Publishing (`TRIP`, `PRG`, `CPN`)
     - **Stage 5:** Traveler Discovery, Seat Hold, Checkout, Multi-Gateway Payment & Invoicing (`TRV`, `BKG`, `PAY`, `ACC`)
     - **Stage 6:** Cancellations, Policy Windows, Coupon Reversal, Remedies & Refunds (`BKG`, `PAY`, `CPN`, `TRIP`)
     - **Stage 7:** Trip Day Operations — Moderator Offline Check-in, Incidents & Live Parent Tracking (`MOD`, `CHK`, `TRK`, `PAR`, `NTF`)
     - **Stage 8:** Post-Trip Media Albums, Double-Entry Ledger, Weekly Supplier Payouts, Settlements & Analytics (`MED`, `PHO`, `ACC`, `ANL`, `API`)
3. **`system/mindmaps.mdx` — Platform Mindmap Atlas:**
   - All 7 master mindmaps with narrative explanations and full `<TraceBar />` jump links.

---

### 5.5 Recommendations & Open-Decision Resolutions Hub (`content/docs/recommendations/` & `content/docs/ar/recommendations/`)

A new top-level documentation folder `recommendations/` (`meta.json`: `index`, `open-decisions`, `architecture-and-ops`):

1. **`recommendations/index.mdx` — Recommendations Playbook & Priority Matrix:**
   - Mindmap of all recommendation tracks + phase-gated action matrix showing exactly what Product, Finance, Legal, Operations, and Engineering should close before `P1`, `P2`, `P3`, `P4`, `P5`, and `P6`.
2. **`recommendations/open-decisions.mdx` — Recommended Resolutions for All 32 `OD-*` Decisions & 15 Inline `[OPEN]` Items:**
   - Covers **every** `Open` and `Proposed` item in `brd/compliance.mdx` §11 (`OD-04`, `OD-05`, `OD-06`, `OD-07`, `OD-08`, `OD-09`, `OD-10`, `OD-11`, `OD-14`, `OD-15`, `OD-16`, `OD-18`, `OD-19`, `OD-25`, `OD-26`, `OD-27`, `OD-28`, `OD-29` resolving the `D-08` downgrade vs `BRL-02` lockout conflict, `OD-30`, `OD-31`, `OD-32` resolving the day-14 vs day-21 extension closure conflict), implementation guidance for all 11 `Decided` items (`OD-01`..`OD-03`, `OD-12`, `OD-13`, `OD-17`, `OD-20`..`OD-24`), **plus all 15 inline `[OPEN]` items across the repository**:
     - `BRD`: `D-314` named approvers & sign-off matrix (`brd/index.mdx`, `brd/compliance.mdx` §12), `D-71` MRR milestone targets (`brd/index.mdx` §2.2), `D-68` child-safety escalation contact & SLA (`brd/compliance.mdx` §8.1).
     - `SRS`: `FR-AUTH-11`/`13` passkey platform rollout order (`srs/requirements/auth.mdx`), `D-216` coupon abuse velocity thresholds (`srs/requirements/cpn.mdx`), `NFR-SEC-13` driver/photographer/parent API rate limits (`srs/quality.mdx`), `NFR-SEC-16` & `erd/domains/platform.mdx` cryptographic hash-chain audit tamper-evidence, `NFR-DAT-04` AWS `me-central-1` + Egypt residency posture (`srs/quality.mdx`), `NFR-OBS-06` 30-day hot / 1-year warm log retention (`srs/quality.mdx`), `NFR-MNT-04` tiered test coverage gates (`srs/quality.mdx`), 3 retention windows in `srs/data.mdx` (rejected applications, ID/lab documents, notification delivery logs), and supported browser/OS minimums (`srs/index.mdx`).
     - `Design`: Arabic primary web font selection (`IBM Plex Sans Arabic` paired with `Inter`, `design/index.mdx`).
   - Every item uses `<RecommendationCard />` with concrete MENA-benchmarked defaults (e.g., VAT: Egypt 14%, KSA 15%, UAE 5%; EGP/SAR/AED role tiers), rationale, trade-offs, affected tables/tasks, and full `<TraceBar />`.
3. **`recommendations/architecture-and-ops.mdx` — Engineering, Security, Payments, Offline & Operations Recommendations:**
   - 12 deep `<RecommendationCard />` blueprints (`REC-ARCH-01` through `REC-OPS-12`):
     1. `REC-ARCH-01`: Modular Monolith Package Boundaries & Transactional Outbox (`SKIP LOCKED` relay)
     2. `REC-DATA-02`: PostgreSQL RLS Connection-Pool Safety (`SET LOCAL app.current_organization_id`) & TimescaleDB Compression
     3. `REC-BOOK-03`: Contention-Free Seat Holds (Redis Lua/TTL + Postgres Conditional Update Defense-in-Depth)
     4. `REC-PAY-04`: Multi-Gateway MENA Payment Routing (Paymob/Fawry in Egypt P2, Moyasar/Tap + Stripe Connect in Gulf P4–P5) & Webhook Idempotency
     5. `REC-TAX-05`: Phased ZATCA Phase 2 (Fatoora XML/TLV QR) & Egyptian ETA E-Receipt Readiness Adapter
     6. `REC-OFF-06`: Moderator Offline-First Check-In Architecture (IndexedDB Queue, Monotonic HLC/Client Timestamps, Conflict UI)
     7. `REC-TRK-07`: Battery-Aware Driver GPS Ingestion, Snap-to-Privacy Rules & WebSocket/SSE Fan-Out
     8. `REC-MED-08`: Resumable Multipart Media Pipeline, Worker Pool Isolation (`libvips`), EXIF Stripping & Signed CDN Cookies
     9. `REC-SEC-09`: Child-Safety Guardrails, Dual-Admin Safety Approvals & Cryptographic Audit Chain
     10. `REC-FIN-10`: Double-Entry Ledger Invariants, Gapless Per-Tenant Invoice Numbering & Daily Gateway Reconciliation
     11. `REC-UX-11`: Arabic-First RTL Engineering, Western Numerals (`0-9`), Cairo/Riyadh/Dubai Timezone Formatting & Seat-Map A11y
     12. `REC-OPS-12`: Progressive Phase Rollout Gates, Load/Chaos Drills (120-Passenger 3-Bus Simulation) & Runbooks
4. **Cross-Links Back From Existing Docs:**
   - Add concise cross-reference links from `brd/compliance.mdx` §11, `plan/p1..p6.mdx` ("Decisions to close"), `plan/milestones.mdx`, `architecture/index.mdx`, and the `[OPEN]` callouts in `srs/index.mdx`, `srs/data.mdx`, `srs/quality.mdx`, `srs/requirements/auth.mdx`, `srs/requirements/cpn.mdx`, `erd/domains/platform.mdx`, and `design/index.mdx` (EN + AR) straight to the matching `<RecommendationCard />` anchors!

---

### 5.6 Navigation, Landing Page, Drift Fixes & Quality Verification

1. **Top-Level Navigation (`content/docs/meta.json` & `content/docs/ar/meta.json`):**
   - Order: `index`, `system`, `brd`, `srs`, `erd`, `user-flows`, `workflows`, `plan`, `architecture`, `design`, `recommendations`, `ai`.
2. **Docs Index, Marketing Landing Page & Drift Repair (`content/docs/index.mdx`, `content/docs/ar/index.mdx`, `src/lib/landing.content.ts`, `user-flows/index.mdx`, `workflows/coverage.mdx`, `srs/quality.mdx`):**
   - Add cards for **End-to-End System** and **Recommendations & Resolutions**.
   - Reconcile exact AST-verified counts everywhere (`213` functional requirements, `28` modules, `14` data domains, `106` role flows, `231` delivery tasks) in both `en` and `ar`.
   - Extend `scripts/coverage-matrix.ts` (or add Arabic sync) so `ar/workflows/coverage.mdx` reflects all `213 / 213` FRs, and repair the malformed nested link in `srs/quality.mdx` (+ AR).
3. **Automated Quality Gate (`scripts/verify-docs-quality.ts`):**
   - Verifies across the entire repository:
     - Every internal `/en/docs/...` and `/ar/docs/...` link (in both Markdown `[text](url)` and JSX `href="..."` / `TraceBar` props) resolves to an existing MDX page and valid heading/element `#anchor`, with zero nested-bracket link syntax errors!
     - Every EN page under `content/docs/` has a matching AR page under `content/docs/ar/` with identical heading count (`link-ids-ar.ts` clean pass).
     - All 213 `FR-*` requirements + 18 platform tasks appear as `<TaskCard />` blocks in both EN and AR phase plans with non-empty descriptions, acceptance criteria, and complete `<TraceBar />` pillars.
     - All 32 `OD-*` items (`OD-01`..`OD-32`) and all 15 inline `[OPEN]` markers resolve to `<RecommendationCard />` anchors in `recommendations/open-decisions.mdx` (+ AR).
     - Zero Mermaid syntax errors across all ```mermaid blocks.
     - `brd/*` contains zero code blocks/diagrams per BRD §1 rule.
   - Run `bun run types:check`, `bun run lint`, `bun scripts/verify-docs-quality.ts`, and `bun run build` (full static prerender of all EN + AR routes).

---

## 6. Builder Calls Made in This Spec

The following implementation details are settled as builder calls (open for user override during review):
1. **BRD §1 Compliance for Mindmaps:** Because BRD §1 explicitly states *"No code, schemas or diagrams in code form"* inside the BRD document, `content/docs/brd/*` will remain diagram-free and instead link to the Business & Commercial Mindmap hosted in `content/docs/system/mindmaps.mdx`.
2. **Heading + `<TaskCard />` Pairing:** Each task emits a standard `### T-P{n}-{MOD}-{seq} — {Title}` heading immediately above `<TaskCard />` so Fumadocs TOC, Orama search, `.md` endpoints, and `scripts/link-ids-ar.ts` work natively with zero custom remark plugins.
3. **Full Repo `OD-01..OD-32` + 15 `[OPEN]` Coverage with MENA-Benchmarked Defaults:** Every `OD-*` item (`OD-01`..`OD-32`) and all 15 inline `[OPEN]` markers across `brd/`, `srs/`, `erd/`, and `design/` receive concrete, MENA-benchmarked recommended defaults (e.g., VAT: Egypt 14%, KSA 15%, UAE 5%; reconciling `OD-29` grace-end lockout vs read-only access and `OD-32` day-21 extension closure) while keeping their honest `Open` / `Proposed` status in `brd/compliance.mdx` until stakeholders sign off.
4. **Pre-existing Count & Coverage Drift Repair:** Reconcile `106` actual `UF-*` flows across `index.mdx`, `user-flows/index.mdx`, and `landing.content.ts`, sync `ar/workflows/coverage.mdx` to `213/213` FRs, and repair the malformed nested link in `srs/quality.mdx`.
