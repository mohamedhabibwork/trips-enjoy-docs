# Trips Enjoy Documentation Full Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (with superpowers:dispatching-parallel-agents for non-overlapping content tracks) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the bilingual (`en` + `ar`) Trips Enjoy documentation site with a reusable MDX component kit, 231 richly detailed delivery tasks per locale, platform-wide Mermaid mindmaps, a dedicated End-to-End System hub, a dedicated Recommendations & Open-Decision Resolutions hub (`OD-01..OD-32` + 15 inline `[OPEN]` items + 12 architectural/operational blueprints), full sidebar wiring, and automated quality verification.

**Architecture:** Global locale-aware React/MDX components (`TraceBar`, `TaskCard`, `RecommendationCard`, `SystemStage`, `MindmapBlock`) wrap MDX children so Fumadocs Orama search, `.md` endpoints, MCP tools, and `/llms-full.txt` index all text. Deterministic Bun generators (`scripts/generate-plan.ts` and `scripts/generate-plan-ar.ts`) synthesize all 231 EN and AR task cards directly from `srs/`, `brd/`, `erd/`, `user-flows/`, and `workflows/`. Two new bilingual folders (`system/` and `recommendations/`) join the sidebar (`meta.json`), and `scripts/verify-docs-quality.ts` gates links, anchors, Mermaid syntax, heading parity, and BRD §1 compliance before `vite build`.

**Tech Stack:** TypeScript 7, React 19, TanStack Start, Fumadocs (`fumadocs-core@16.18`, `fumadocs-mdx@15.5`, `@fumadocs/base-ui@16.18`), Tailwind CSS v4, Mermaid v12.3, Bun, Oxlint.

**Spec:** `docs/superpowers/specs/2026-11-11-docs-upgrade-design.md`

## Global Constraints

- **Working Tree Preservation Ruling:** `content/docs/ar/`, `content/docs/architecture/`, `content/docs/design/`, and several scripts are currently untracked/unstaged in the user's active workspace `/Users/habib/work/docs/trips-enjoy-docs/trip-docs`. Work directly in this workspace and do not run destructive git commands or partial worktrees that would lose untracked directories.
- **BRD §1 Rule:** `content/docs/brd/*` and `content/docs/ar/brd/*` must contain zero fenced code blocks or Mermaid diagrams; host business/commercial mindmaps in `system/mindmaps.mdx` and link to them.
- **EN↔AR Heading Parity:** Every `content/docs/<rel>.mdx` and `content/docs/ar/<rel>.mdx` pair must maintain identical Markdown heading counts and positional order so `bun scripts/link-ids-ar.ts` maps anchors with zero mismatches.
- **Mermaid v12 Syntax Safety:** Never use unquoted parentheses `(` `)` or brackets `[` `]` inside Mermaid `mindmap` plain labels or flowchart unquoted labels; validate every Mermaid block programmatically.
- **Full Inline Detail + Full Cross-Links:** No link-only stub cards; every `<TaskCard />`, `<SystemStage />`, and `<RecommendationCard />` must carry complete inline prose/criteria plus a `<TraceBar />` linking `BRD`, `SRS`, `ERD`, `Plan`, `Workflows`, `User Flows`, `Architecture`, and `Design`.

## Review Focus

1. **Mermaid `mindmap` parse failures on special characters (`(`, `)`, `[`, `]`, `:`)** — every mindmap in EN and AR must parse cleanly in `mermaid@12.3` without falling back to the red error box.
2. **JSX prop serialization in generated MDX (`scripts/generate-plan*.ts`)** — quotes, braces, or special characters in FR titles or links inside `<TaskCard trace={...} />` must serialize via `JSON.stringify` so MDX compilation never fails.
3. **Broken `#anchor` fragments across `/en/docs/...` and `/ar/docs/...`** — every link in Markdown and inside `TraceBarProps` must match a real `github-slugger` heading slug or element `id` on the target page.
4. **Search / `.md` / `/llms-full.txt` emptiness** — substantive prose must live in MDX `children`, not exclusively inside JSX props, so `getText('processed')` and Orama search index full task/stage/recommendation details.
5. **Sidebar navigation & mobile/RTL rendering** — `meta.json` in root and subfolder directories (`en` + `ar`) must expose clear titles, icons, separators/groups, and working links across LTR and RTL.

---

### Task 1: Reusable MDX Component Kit & Sidebar Chrome

**Files:**
- Create: `src/components/docs-kit/trace-bar.tsx`
- Create: `src/components/docs-kit/task-card.tsx`
- Create: `src/components/docs-kit/recommendation-card.tsx`
- Create: `src/components/docs-kit/system-stage.tsx`
- Create: `src/components/docs-kit/mindmap-block.tsx`
- Create: `src/components/docs-kit/index.ts`
- Modify: `src/components/mdx/index.tsx`
- Modify: `content/docs/meta.json`
- Modify: `content/docs/ar/meta.json`
- Create: `content/docs/system/meta.json`, `content/docs/ar/system/meta.json`
- Create: `content/docs/recommendations/meta.json`, `content/docs/ar/recommendations/meta.json`
- Modify: `src/lib/layout.shared.tsx` (add quick top-nav/sidebar links so key hubs are 1 click away)

**Interfaces:**
- Produces global MDX components:
  - `TraceBar(props: TraceBarProps)` where `TraceBarProps` accepts `brd`, `srs`, `erd`, `plan`, `workflows`, `userFlows`, `architecture`, `design`, `recommendations` (`TraceLinkItem[]`, each `{ label: string; href: string; detail?: string }`) and optional `compact?: boolean`.
  - `TaskCard(props: TaskCardProps)` with `id`, `title`, `phase`, `module`, `priority`, `kind?: 'functional' | 'platform'`, `surfaces?: string[]`, `tables?: string[]`, `trace: TraceBarProps`, `children: ReactNode`.
  - `RecommendationCard(props: RecommendationCardProps)` with `id`, `title`, `category`, `currentStatus`, `targetPhase`, `owner`, `impact`, `recommendedResolution`, `trace: TraceBarProps`, `children: ReactNode`.
  - `SystemStage(props: SystemStageProps)` with `stageNumber`, `id`, `title`, `phaseIntroduced`, `actors`, `stateMachines?`, `tablesWritten?`, `eventsEmitted?`, `trace: TraceBarProps`, `children: ReactNode`.
  - `MindmapBlock(props: MindmapBlockProps)` with `title`, `caption`, `trace?: TraceBarProps`, `children: ReactNode`.

- [ ] **Step 1:** Implement `src/components/docs-kit/*` with full bilingual EN/AR UI chrome, RTL-safe Tailwind v4 logical classes, and register all 5 components in `src/components/mdx/index.tsx`.
- [ ] **Step 2:** Wire root and subfolder sidebar `meta.json` files (EN + AR) with section groupings, icons, and titles for `system` and `recommendations`, plus top-level navigation links in `src/lib/layout.shared.tsx`.
- [ ] **Step 3:** Run `bun run types:check` and `bun run lint` to verify clean TypeScript and lint compilation.

---

### Task 2: End-to-End System Hub & Mindmap Atlas (`EN` + `AR`)

**Files:**
- Create: `content/docs/system/index.mdx`, `content/docs/ar/system/index.mdx`
- Create: `content/docs/system/lifecycle.mdx`, `content/docs/ar/system/lifecycle.mdx`
- Create: `content/docs/system/mindmaps.mdx`, `content/docs/ar/system/mindmaps.mdx`

**Interfaces:**
- Consumes: `SystemStage`, `MindmapBlock`, `TraceBar` from Task 1.
- Produces: Canonical anchors `#stage-01`..`#stage-08` (via standard Markdown headings) and 7 master mindmaps (Ecosystem/Roles, Business/Commercial, 28 SRS Modules, 14 ERD Domains, 10 Workflows/Jobs, P1–P6 Roadmap, NFR/Compliance).

- [ ] **Step 1:** Author `content/docs/system/index.mdx`, `lifecycle.mdx` (8 `<SystemStage />` sections covering Onboarding → Supplier Deals → Fleet/Seats → Trip Publishing → Booking/Payment → Refunds/Remedies → Trip-Day Offline Check-in & Live Parent Tracking → Media Albums, Ledger & Weekly Payouts), and `mindmaps.mdx`.
- [ ] **Step 2:** Author full, natural Arabic mirrors in `content/docs/ar/system/index.mdx`, `lifecycle.mdx`, and `mindmaps.mdx` with 1:1 positional heading parity.

---

### Task 3: Recommendations & Open-Decision Resolutions Hub (`EN` + `AR`) + Back-Links

**Files:**
- Create: `content/docs/recommendations/index.mdx`, `content/docs/ar/recommendations/index.mdx`
- Create: `content/docs/recommendations/open-decisions.mdx`, `content/docs/ar/recommendations/open-decisions.mdx`
- Create: `content/docs/recommendations/architecture-and-ops.mdx`, `content/docs/ar/recommendations/architecture-and-ops.mdx`
- Modify: `content/docs/brd/compliance.mdx`, `content/docs/ar/brd/compliance.mdx`
- Modify: `content/docs/brd/index.mdx`, `content/docs/ar/brd/index.mdx`
- Modify: `content/docs/srs/index.mdx`, `content/docs/ar/srs/index.mdx`
- Modify: `content/docs/srs/data.mdx`, `content/docs/ar/srs/data.mdx`
- Modify: `content/docs/srs/quality.mdx`, `content/docs/ar/srs/quality.mdx` (also fix malformed `[NFR-[DAT-04](...)]` link)
- Modify: `content/docs/srs/requirements/auth.mdx`, `content/docs/ar/srs/requirements/auth.mdx`
- Modify: `content/docs/srs/requirements/cpn.mdx`, `content/docs/ar/srs/requirements/cpn.mdx`
- Modify: `content/docs/erd/domains/platform.mdx`, `content/docs/ar/erd/domains/platform.mdx`

**Interfaces:**
- Consumes: `RecommendationCard`, `MindmapBlock`, `TraceBar` from Task 1.
- Produces: Deterministic heading anchors for all `OD-01`..`OD-32`, all 15 inline `[OPEN]` resolutions (`OPEN-01`..`OPEN-15`), and `REC-ARCH-01`..`REC-OPS-12` in `recommendations/open-decisions.mdx` and `recommendations/architecture-and-ops.mdx` (EN + AR).

- [ ] **Step 1:** Author `recommendations/index.mdx`, `recommendations/open-decisions.mdx` (all 32 `OD-*` + 15 `[OPEN]` items with concrete MENA-benchmarked resolutions), and `recommendations/architecture-and-ops.mdx` (12 deep engineering/security/payment/offline/ops recommendations) in English and Arabic with 1:1 heading parity.
- [ ] **Step 2:** Add recommended-resolution cross-links from `brd/compliance.mdx`, `brd/index.mdx`, and inline `[OPEN]` locations across `srs/` and `erd/` (plus fix the malformed nested link in `srs/quality.mdx`).

---

### Task 4: Upgraded Bilingual Task Generators (`P1`–`P6` + `Milestones` + `Coverage`)

**Files:**
- Modify: `scripts/generate-plan.ts`
- Modify: `scripts/generate-plan-ar.ts`
- Modify: `scripts/coverage-matrix.ts` (sync `ar/workflows/coverage.mdx` to `213/213` FRs)
- Regenerate: `content/docs/plan/p1.mdx`..`p6.mdx`, `content/docs/plan/milestones.mdx`
- Regenerate: `content/docs/ar/plan/p1.mdx`..`p6.mdx`, `content/docs/ar/plan/milestones.mdx`
- Regenerate: `content/docs/workflows/coverage.mdx`, `content/docs/ar/workflows/coverage.mdx`

**Interfaces:**
- Consumes: `TaskCard`, `MindmapBlock`, `TraceBar` from Task 1; `OD-*` and `REC-*` anchors from Task 3.
- Produces: 213 functional `<TaskCard />` blocks + 18 `<TaskCard kind="platform" />` blocks = 231 rich tasks per locale, phase mindmaps, quick-jump tables, and enriched milestone/decision gates.

- [ ] **Step 1:** Upgrade `scripts/generate-plan.ts` and `scripts/generate-plan-ar.ts` to parse full SRS prose + Given/When/Then bullets, exact ERD table anchors (`## table_name`), BRD `BR-*` & `BRL-*` statements, state machines, scheduled jobs, architecture/design surfaces, and `OD-*`/`REC-*` links, emitting per-phase `<MindmapBlock />`, summary index tables, and 231 `<TaskCard />` blocks per locale.
- [ ] **Step 2:** Upgrade `scripts/coverage-matrix.ts` to also generate/update `content/docs/ar/workflows/coverage.mdx` for full `213/213` parity.
- [ ] **Step 3:** Run `bun scripts/coverage-matrix.ts`, `bun scripts/generate-plan.ts`, and `bun scripts/generate-plan-ar.ts` and verify output counts and heading parity.

---

### Task 5: Section Index Mindmaps, Landing Page & Top-Level Docs Index (`EN` + `AR`)

**Files:**
- Modify: `content/docs/index.mdx`, `content/docs/ar/index.mdx`
- Modify: `src/lib/landing.content.ts`
- Modify: `content/docs/erd/index.mdx`, `content/docs/ar/erd/index.mdx`
- Modify: `content/docs/user-flows/index.mdx`, `content/docs/ar/user-flows/index.mdx`
- Modify: `content/docs/workflows/index.mdx`, `content/docs/ar/workflows/index.mdx`
- Modify: `content/docs/plan/index.mdx`, `content/docs/ar/plan/index.mdx`
- Modify: `content/docs/architecture/index.mdx`, `content/docs/ar/architecture/index.mdx`
- Modify: `content/docs/design/index.mdx`, `content/docs/ar/design/index.mdx` (also link Arabic font `[OPEN]` resolution)

- [ ] **Step 1:** Add domain `<MindmapBlock />` diagrams with `<TraceBar />` jump links to `srs/index.mdx` (if not added in Task 3, do here cleanly), `erd/index.mdx`, `user-flows/index.mdx` (updating flow count to `106`), `workflows/index.mdx`, `plan/index.mdx`, `architecture/index.mdx`, and `design/index.mdx` in both EN and AR with exact heading parity.
- [ ] **Step 2:** Update `content/docs/index.mdx`, `content/docs/ar/index.mdx`, and `src/lib/landing.content.ts` with cards/sections for **End-to-End System** and **Recommendations & Resolutions** and accurate counts (`213` FRs, `28` modules, `14` domains, `106` role flows, `231` tasks).

---

### Task 6: Anchor Sync, Automated Quality Verification Suite & Full Prerender Build

**Files:**
- Create: `scripts/verify-docs-quality.ts`
- Regenerate: `src/lib/ar-anchors.ts` (via `bun scripts/link-ids-ar.ts`)

- [ ] **Step 1:** Run `bun scripts/link-ids-ar.ts` and confirm zero EN↔AR heading-count mismatches across the entire repository.
- [ ] **Step 2:** Write `scripts/verify-docs-quality.ts` testing:
  1. 100% internal link + `#anchor` resolution across all EN and AR MDX files (Markdown links, JSX `href` attributes, and `TraceBar` prop URLs).
  2. Zero nested-bracket malformed links.
  3. 1:1 EN↔AR file and heading parity.
  4. All 213 `FR-*` + 18 `PLAT` = 231 tasks present in both EN and AR phase plans with non-empty specification, acceptance criteria, and multi-pillar `trace`.
  5. All 32 `OD-*` and 15 `[OPEN]` markers covered in `recommendations/open-decisions.mdx` (+ AR).
  6. Every ```mermaid block across all EN and AR MDX files parses cleanly via `mermaid` / strict grammar validation, and `brd/*` contains zero code/diagram fences.
- [ ] **Step 3:** Run `bun scripts/verify-docs-quality.ts`, `bun run types:check`, `bun run lint`, and `bun run build` (full static prerender), fixing any findings until all four exit `0`.
