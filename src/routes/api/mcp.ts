import { createFileRoute } from '@tanstack/react-router';
import { WebStandardStreamableHTTPServerTransport } from '@modelcontextprotocol/server';
import { createMcpServer } from '@/lib/mcp';

/**
 * MCP endpoint on the official fumadocs MCP utilities
 * (fumadocs.dev/docs/headless/utils/mcp) + the MCP SDK's web-standard
 * streamable HTTP transport, in stateless mode (no sessions): a fresh
 * McpServer + transport is created per POST, so concurrent requests never
 * share state.
 *
 * Tools (registered in src/lib/mcp.ts):
 *   list_pages — page index as llms.txt (both locales)
 *   get_page   — one page as Markdown, /en/docs/... and /ar/docs/... URLs
 *   search     — full-text search with optional locale
 *
 * No auth — the underlying docs are public. See /en/docs/ai.
 */

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Accept, Mcp-Session-Id, Authorization',
  'Access-Control-Expose-Headers': 'Mcp-Session-Id',
} as const;

const SERVER_INFO = { name: 'trips-enjoy-docs', version: '1.0.0' };

function withCors(response: Response): Response {
  for (const [key, value] of Object.entries(CORS)) response.headers.set(key, value);
  return response;
}

export const Route = createFileRoute('/api/mcp')({
  server: {
    handlers: {
      OPTIONS: () => new Response(null, { status: 204, headers: CORS }),

      GET: () =>
        Response.json(
          {
            server: SERVER_INFO,
            transport: 'streamable-http (stateless, MCP SDK web transport)',
            endpoint: 'POST /api/mcp — JSON-RPC 2.0 (initialize, tools/list, tools/call)',
          },
          { headers: CORS },
        ),

      POST: async ({ request }) => {
        // stateless: every request gets its own server + transport pair
        const server = createMcpServer();
        const transport = new WebStandardStreamableHTTPServerTransport({
          sessionIdGenerator: undefined,
          enableJsonResponse: true,
        });
        await server.connect(transport);

        try {
          return withCors(await transport.handleRequest(request));
        } catch (error) {
          console.error('[mcp] request failed:', error);
          return Response.json(
            {
              jsonrpc: '2.0',
              id: null,
              error: { code: -32603, message: `Internal error: ${String(error)}` },
            },
            { status: 500, headers: CORS },
          );
        }
      },
    },
  },
});
