# Trips Enjoy Docs

The product documentation site for **Trips Enjoy** — a multi-role trips marketplace — built with
[Fumadocs](https://fumadocs.dev) on TanStack Start. It publishes the complete specification set as
a linked, searchable, component-driven site:

| Section | Content |
| --- | --- |
| Business (BRD) | Objectives, scope, stakeholders, commercial model, 101 BR-requirements, 30 business rules, risks, open decisions |
| Software (SRS) | 201 functional requirements across 28 module pages (MoSCoW + traceability), data requirements, 10 state machines, NFRs, BRD↔SRS traceability |
| Data Model (ERD) | 14 domain pages with live mermaid relationship diagrams, entity dictionaries, lookup catalog, row-level security paths |
| User Flows | 95 role flows (traveler, organization, admin, driver, photographer, moderator, parent, shared) as step-by-step steppers |
| For AI Agents | Machine-readable endpoints, see below |

Every requirement ID (`FR-*`, `BR-*`, `BRL-*`, `NFR-*`, `DAT-*`, `OD-*`, `UF-*`) is cross-linked
to its definition. `D-*` IDs refer to an external decision register and stay as plain text.

## Commands

```bash
bun install        # install dependencies
bun run dev        # dev server on http://localhost:3000
bun run build      # production build (prerenders every docs page)
bun run types:check
bun run lint       # oxlint
```

## Structure

```
content/docs/          # canonical documentation (MDX) — edit here
src/                   # Fumadocs app (TanStack Start, i18n ar/en)
scripts/convert-specs.ts  # record of the one-time .md → MDX migration (already run)
scripts/link-ids.ts       # record of the one-time ID cross-linking pass (already run)
archive/specs/         # original BRD.md / SRS.md / ERD.md / user-flows/ sources
```

MDX under `content/docs` is the source of truth — the scripts above were one-time migrations and
re-running them would overwrite manual edits.

## Connecting AI agents

The whole set is machine-readable, no keys required:

| Endpoint | Returns |
| --- | --- |
| `/llms.txt` | Curated index of the documentation set |
| `/llms-full.txt` | Everything in one Markdown document |
| `/{en|ar}/docs/<path>.md` | Any page as Markdown, e.g. `/en/docs/srs/requirements/auth.md` |
| `/api/search?query=…` | Full-text search with section anchors |
| `/api/mcp` | MCP server (Streamable HTTP, JSON-RPC 2.0); `GET /api/mcp` returns server info |

MCP tools: `list_docs`, `search_docs` (query), `get_doc` (path → Markdown).
Example client config:

```json
{
  "mcpServers": {
    "trips-enjoy-docs": {
      "type": "http",
      "url": "https://<your-host>/api/mcp"
    }
  }
}
```

Quick test:

```bash
curl -X POST http://localhost:3000/api/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_docs","arguments":{"query":"seat hold"}}}'
```

## i18n

Routes are locale-prefixed (`/en/docs`, `/ar/docs`). Content files without a locale suffix resolve
to the default language (English); Arabic pages are added as `*.ar.mdx` siblings.
