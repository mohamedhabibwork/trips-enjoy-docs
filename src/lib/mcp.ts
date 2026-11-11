import { McpServer } from '@modelcontextprotocol/server';
import { registerSourceTools, registerSearchTool } from 'fumadocs-core/mcp';
import { source, docsLlms } from './source';
import { searchServer } from './search-server';

/**
 * MCP server built on the official fumadocs headless MCP utilities
 * (fumadocs.dev/docs/headless/utils/mcp):
 *
 * - `list_pages` — the full page index (`docsLlms.index()`, both locales)
 * - `get_page`   — one page as Markdown; i18n-aware lookup (`/en/docs/...` and `/ar/docs/...` URLs both resolve)
 * - `search`     — the shared per-locale search index (same one behind /api/search)
 *
 * A fresh server instance is created per request by the route (stateless
 * streamable HTTP — no sessions).
 */
export function createMcpServer() {
  const mcp = new McpServer(
    { name: 'trips-enjoy-docs', version: '1.0.0' },
    {
      instructions:
        'Trips Enjoy product documentation: business requirements (BRD, 107 BRs + 31 BRL rules), software requirements (SRS, 213 FRs), data model (ERD), user flows (UF) and the generated delivery plan. Search first, then fetch pages as Markdown. URLs are locale-prefixed: /en/docs/... and /ar/docs/... both work.',
    },
  );

  // `list_pages` & `get_page` — i18n-aware page lookup
  registerSourceTools(mcp, source, docsLlms);
  // `search` — locale-aware full-text search over the shared index
  registerSearchTool(mcp, searchServer);

  return mcp;
}
