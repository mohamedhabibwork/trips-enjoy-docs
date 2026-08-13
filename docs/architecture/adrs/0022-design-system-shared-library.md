# ADR-0022: Cross-Stack Design System as a First-Class Shared Library

- Status: Accepted
- Date: 2026-08-12
- Authors: Platform Architecture + Design Systems team
- Deciders: Architecture Review Board
- Tags: design-system, frontend, web, mobile, react, flutter, design-tokens, a11y, rtl, i18n

## Context and Problem Statement

The platform has multiple customer / operator surfaces that all render
the same brand:

- **Web**: Vue 3 (Composition API, `<script setup>`) + TypeScript
  + HeadlessUI Vue + TanStack Query for Vue + Vite-based SSR
  (Nuxt 3 or Vike) — customer web
  app, driver web app, courier web app, restaurant operator
  console, admin console, partner portal.
- **Mobile**: Flutter 3.44 (Dart 3.12) — customer mobile app,
  driver mobile app, courier mobile app, restaurant staff app.

Each surface ships its own button, its own form, its own color
palette, its own typography, its own a11y patterns. The result is:

- **Visual drift** — the customer web's Button does not match the
  customer's mobile Button; the admin console's color palette
  diverges from the operator console's; the partner portal uses
  a different shade of "primary blue" than the customer app.
- **Behavioural drift** — keyboard navigation patterns differ
  between web apps; focus rings differ; ARIA roles differ.
- **i18n / RTL drift** — Arabic (RTL) is implemented ad-hoc per
  app; the EN → AR translation is duplicated; RTL bugs are
  filed in each repo.
- **A11y drift** — WCAG audit scores differ per app; some apps
  meet AA, some don't.
- **White-label cost** — a partner wants to re-brand the platform
  to their brand (a different color, a different font, their
  logo) and the answer is "fork the consumer app", because no
  shared token system exists.
- **Translation cost** — every app maintains its own translation
  catalog; the same string is translated 5 times; the
  translation vendor's API is integrated per app.

The platform's [`main.md` §1](../../../main.md) commits to "TanStack
Start (web) + Flutter (mobile) + a shared design system"; the
is the single source for the runtime stack; the backend has the
`platform-spring-boot-starter` shared library. There is no
**frontend** equivalent.

## Decision Drivers

- **Visual + behavioural consistency** across all surfaces is a
  pre-requisite for a coherent brand and a coherent product
  story.
- **i18n parity (EN + AR + future locales)** is a launch
  requirement (per the customer-service locale preference and
  the platform's commitment to ship AR from day one). The cost
  of N independent i18n catalogs is unacceptable.
- **RTL parity** for Arabic is non-negotiable. The current
  per-app RTL implementations are inconsistent and bug-prone.
- **Accessibility (WCAG 2.2 AA)** is a platform commitment.
  Without a shared design system, a11y has to be re-implemented
  in every app and audited per app.
- **White-label** is a go-to-market lever; re-branding without
  a token system is a per-customer fork.
- **The backend has the `platform-spring-boot-starter`
  pattern** (see [`shared/README.md`](../../shared/README.md)).
  The frontend needs the same pattern.

## Considered Options

- **Option A — Per-app ad-hoc design system.** Each app
  maintains its own components, tokens, and a11y patterns.
  *Rejected*: this is the current state; the drift is the
  problem we're solving.

- **Option B — One design system, one stack.** Adopt one
  framework (e.g. Vue for web, or Flutter for mobile) for
  the design system, and require all apps to use it. *Rejected*:
  the platform has a documented web + mobile split. Forcing one
  stack would either eliminate one surface (unacceptable) or
  force one surface to render in a non-native framework
  (poor UX).

- **Option C — Per-stack design system (web + mobile) with a
  shared W3C token layer.** Adopt
  `@trips-enjoy/design-system` (Vue 3 + TypeScript) for web, and
  `package:trips_enjoy_ds` (Flutter 3.44) for mobile, with the **only
  shared artifact** being the W3C Design Tokens Community Group
  JSON. Each stack implements components natively but consumes
  the same tokens, follows the same a11y contract, ships the
  same i18n catalog, and matches the same visual regression
  test suite. *Chosen* — keeps each stack native, gives a
  single source of truth for tokens + i18n + a11y, and avoids
  forcing one stack to render the other.

## Decision Outcome

Chosen option: **C — Per-stack design system with a shared W3C
token layer.**

The full architecture is in
[`shared/DESIGN_SYSTEM.md`](../../shared/DESIGN_SYSTEM.md). Summary:

- **Web** — `@trips-enjoy/design-system` (Vue 3 + TypeScript, built
  on HeadlessUI Vue primitives for a11y, styled with Tailwind CSS 4 +
  CVA, distributed via private npm with `--provenance`).
- **Mobile** — `package:trips_enjoy_ds` (Flutter 3.44, wrapped Material
  3 + Cupertino widgets, distributed via private pub).
- **Shared tokens** — `@trips-enjoy/design-tokens` + `package:trips_enjoy_ds_tokens`
  (W3C Design Tokens JSON, codegen to TS types + CSS variables
  on web, and to Dart `const` + `ThemeData` factory on mobile).
- **i18n** — `@trips-enjoy/ds-i18n` (web, FormatJS) + `package:trips_enjoy_ds_i18n`
  (mobile, ARB files + `flutter gen-l10n`), single translation
  catalog round-tripped with the platform's vendor.
- **A11y** — WCAG 2.2 AA floor with AAA for the brand palette
  where achievable; `jest-axe` (web) + Flutter a11y
  integration tests (mobile) as CI gates.
- **RTL** — Arabic as a first-class rendering target; logical
  properties only (`padding-inline-start` not `padding-left`);
  `prefers-reduced-motion` guard; RTL visual regression test
  in Playwright.
- **Theming** — runtime-swappable token set for white-label
  (load token JSON from `configuration-service` at startup;
  one swap = full re-theme with no rebuild).
- **Adoption contract** — apps **must** import from the
  package; copy-paste of a Button is a build error (enforced
  by `@trips-enjoy/no-hard-coded-colors`, `@trips-enjoy/no-hard-coded-strings`,
  and the Flutter lints).

### Consequences

- Good: visual + behavioural + i18n + RTL + a11y consistency
  across all surfaces; one place to evolve the brand.
- Good: per-stack native performance and developer experience
  (Vue 3 for the web team, Flutter for the mobile team).
- Good: white-label becomes a token swap, not a fork.
- Good: the design-system team has a single CI to enforce
  a11y, i18n completeness, visual regression, and bundle
  size.
- Good: the platform's `main.md` "Frontend stack" commitment
  is now backed by an architecture doc + an ADR.
- Neutral: two stacks to maintain (web + mobile). The cross-
  stack contract is the W3C tokens + the a11y contract +
  the i18n catalog; everything below tokens is native per
  stack.
- Neutral: every app must add a CI gate
  (`@trips-enjoy/no-hard-coded-colors`, `jest-axe`, Playwright
  RTL visual regression); this is a 1-time setup per app.
- Bad: the design system is a new repo to maintain
  (alongside the platform's 21-service backend). The
  design-system team needs ongoing capacity.
- Bad: per-stack component parity requires a cross-stack
  visual regression test (Chromatic on web, Percy on
  mobile); the contract that the two stacks render the
  same is enforced visually, not structurally.
- Bad: an app team that needs a component the design
  system doesn't ship must go through the design-system
  team's RFC process (not a 5-minute fork). This is the
  intended cost of preventing drift.

### Confirmation

- The design system architecture is in
  [`shared/DESIGN_SYSTEM.md`](../../shared/DESIGN_SYSTEM.md)
  with 15 sections covering stack, tokens, components,
  i18n/RTL, a11y, theming, distribution, versioning,
  quality gates, adoption contract, anti-patterns, and
  open questions.
- The architecture is cross-referenced from
  [`README.md` 31a](../../README.md),
  [`architecture/ARCHITECTURE.md`](../../architecture/ARCHITECTURE.md)
  (Layered View, Channel Layer note),
  [`architecture/HLD.md` §3.2](../../architecture/HLD.md)
  (Container view, Channel Layer callout), and
  [`shared/README.md`](../../shared/README.md)
  (sibling note).
- Every consumer surface (web apps + Flutter apps) is
  expected to:
  - import from `@trips-enjoy/design-system` / `package:trips_enjoy_ds`,
  - consume the W3C token JSON (no hard-coded colors,
    spacing, or strings),
  - pass the `jest-axe` / `flutter a11y` / Playwright
    RTL visual regression CI gates,
  - ship AR (RTL) at parity with EN.
- The design-system team owns:
  - the token source of truth in Figma Variables,
  - the W3C JSON export pipeline (CI diffs against the
    last published version),
  - the `jest-axe` / Flutter a11y / Playwright visual
    regression CI gates,
  - the `@trips-enjoy/ds-i18n` translation catalog.

## References

- [`../../shared/DESIGN_SYSTEM.md`](../../shared/DESIGN_SYSTEM.md) —
  the design system architecture (this ADR's full detail).
- [`../../shared/README.md`](../../shared/README.md) — the
  `platform-spring-boot-starter` shared library; the design
  system is the **frontend sibling**.
- [`../../../main.md`](../../../main.md) — top-level platform
  specification (the web + mobile stack is decided here).
- [`../../architecture/ARCHITECTURE.md`](../../architecture/ARCHITECTURE.md) —
  Layered View (Channel Layer is L0).
- [`../../architecture/HLD.md`](../../architecture/HLD.md) —
  High-Level Design (Container view).
- [`../../architecture/SYSTEM_OVERVIEW.md`](../../architecture/SYSTEM_OVERVIEW.md) —
  plain-English platform summary.
- [`../../services/RECOMMENDATIONS.md`](../../services/RECOMMENDATIONS.md) —
  the technology map; the design system is the frontend
  equivalent.
- [`../../shared/CONVENTIONS.md`](../../shared/CONVENTIONS.md) —
  code conventions; the design-system ESLint + Flutter
  lint rules follow §3.
- [`../../shared/OSS_DEPENDENCIES.md`](../../shared/OSS_DEPENDENCIES.md) —
  the SPDX license catalogue; the design-system OSS
  libraries (HeadlessUI Vue, Tailwind, CVA, lucide, etc.)
  are listed there.
- [`../../shared/PLATFORM_BASELINE.md`](../../shared/PLATFORM_BASELINE.md) —
  runtime stack baseline.
- [W3C Design Tokens Community Group format](https://design-tokens.github.io/community-group/format/) —
  the cross-stack token format.
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/) — the a11y
  standard.
- [ICU MessageFormat](https://unicode-org.github.io/icu/userguide/format_parse/messages/) —
  the i18n format used by both stacks.
