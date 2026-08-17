---
name: trips-enjoy-architecture
description: Safely evolve the Trips Enjoy microservices architecture and documentation. Use when changing service boundaries, API or event contracts, ownership, persistence, security, resilience, observability, deployment, or architecture decisions.
---

# Trips Enjoy Architecture

Apply the [shared Code Quality Gate](../AGENTS.md) before handoff.

Treat `main.md` and `docs/` as the authoritative architecture source.

## Workflow

1. Read `docs/README.md`, then the affected service documentation and the
   relevant shared architecture documents before proposing a change.
2. Identify the source of truth for data, APIs, events, security, and the
   affected failure path. Preserve service autonomy: no cross-service database
   access or foreign keys.
3. Keep REST APIs URI-versioned, events versioned, money in minor units, and
   timestamps in UTC. Specify idempotency, correlation, retries, and
   compensations for distributed changes.
4. Update all impacted artifacts together: service README, SRS, ERD,
   integration contract, workflows, technical profile, implementation plan,
   and shared architecture documents or ADRs.
5. Validate Mermaid, Markdown links, requirement IDs, terminology, and
   contract consistency. Report unresolved cross-service decisions.

## Security Gate

- Apply least privilege, data minimization, and defense in depth.
- Never place credentials, tokens, payment data, raw locations, or unnecessary
  PII in documentation examples, events, logs, traces, or test fixtures.
- Document authentication, authorization, audit, retention, encryption, and
  incident-relevant observability whenever a boundary or data flow changes.
