import { createFileRoute } from '@tanstack/react-router';
import { source, docs } from '@/lib/source';
import { i18n } from '@/lib/i18n';
import { searchServer } from '@/lib/search-server';

/**
 * Stateless MCP server (Streamable HTTP transport, JSON-RPC 2.0).
 *
 * Tools:
 *   list_docs   — every page as {title, url}
 *   search_docs — full-text search with section anchors (same index as /api/search)
 *   get_doc     — one page as processed Markdown (same source as llms.txt)
 *
 * No sessions, no auth — the underlying docs are public. See /en/docs/ai.
 */

const PROTOCOL_VERSION = '2025-06-18';
const SERVER_INFO = { name: 'trips-enjoy-docs', version: '1.0.0' };

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Accept, Mcp-Session-Id, Authorization',
  'Access-Control-Expose-Headers': 'Mcp-Session-Id',
} as const;

type JsonRpcId = string | number | null;

function ok(id: JsonRpcId, result: unknown) {
  return Response.json({ jsonrpc: '2.0', id, result }, { headers: CORS });
}

function rpcError(id: JsonRpcId, code: number, message: string) {
  return Response.json(
    { jsonrpc: '2.0', id, error: { code, message } },
    { headers: CORS },
  );
}

const textResult = (text: string) => ({ content: [{ type: 'text' as const, text }] });

const TOOLS = [
  {
    name: 'list_docs',
    description: 'List every documentation page with its title and URL.',
    inputSchema: { type: 'object' as const, properties: {}, additionalProperties: false },
  },
  {
    name: 'search_docs',
    description:
      'Full-text search across all pages. Returns ranked hits with URLs and section anchors. Use this first to find which page covers a topic or requirement ID (FR-*, BR-*, BRL-*, UF-*).',
    inputSchema: {
      type: 'object' as const,
      properties: {
        query: { type: 'string', description: 'Search query, e.g. "seat hold" or "FR-SEAT-02"' },
      },
      required: ['query'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_doc',
    description:
      'Return one documentation page as full Markdown. `path` is the page path without the /en/docs prefix, e.g. "srs/requirements/auth" or "user-flows/traveler".',
    inputSchema: {
      type: 'object' as const,
      properties: {
        path: { type: 'string', description: 'Page path, e.g. "srs/requirements/auth"' },
      },
      required: ['path'],
      additionalProperties: false,
    },
  },
];

// ── tool implementations ───────────────────────────────────────────────

type TreeNode = {
  type: 'page' | 'folder' | 'separator';
  name?: string;
  url?: string;
  children?: TreeNode[];
  index?: TreeNode;
};

function flattenPages(node: TreeNode, out: { title: string; url: string }[]) {
  if (node.type === 'page' && node.url) {
    out.push({ title: node.name ?? node.url, url: node.url });
  }
  if (node.type === 'folder') {
    if (node.index) flattenPages(node.index, out);
    for (const child of node.children ?? []) flattenPages(child, out);
  }
}

async function callTool(name: string, args: Record<string, unknown>) {
  if (name === 'list_docs') {
    const tree = source.getPageTree(i18n.defaultLanguage) as unknown as TreeNode;
    const pages: { title: string; url: string }[] = [];
    for (const child of tree.children ?? []) flattenPages(child, pages);
    return textResult(JSON.stringify(pages, null, 2));
  }

  if (name === 'search_docs') {
    const query = String(args.query ?? '').trim();
    if (!query) return { content: [], isError: true };
    const hits = await searchServer.search(query, { limit: 10 });
    return textResult(JSON.stringify(hits, null, 2));
  }

  if (name === 'get_doc') {
    const raw = String(args.path ?? '').trim();
    const path = raw.replace(/^\/?(en\/)?docs\/?/, '').replace(/\.md$/, '');
    const page = source.getPage(path ? path.split('/') : [], i18n.defaultLanguage);
    if (!page) {
      return {
        content: [{ type: 'text' as const, text: `Page not found: ${path}. Use list_docs to see valid paths.` }],
        isError: true,
      };
    }
    await docs.getPage(page.path)?.preload();
    const markdown = await page.data.getText('processed');
    return textResult(`# ${page.data.title}\n\n${markdown ?? ''}`);
  }

  return { content: [{ type: 'text' as const, text: `Unknown tool: ${name}` }], isError: true };
}

// ── JSON-RPC dispatch ──────────────────────────────────────────────────

async function handleRpc(body: unknown): Promise<Response | null> {
  const msg = body as { jsonrpc?: string; id?: JsonRpcId; method?: string; params?: Record<string, unknown> };
  if (typeof msg !== 'object' || msg === null || typeof msg.method !== 'string') {
    return rpcError(null, -32600, 'Invalid Request');
  }
  // notifications (no id) get no response body
  const isNotification = msg.id === undefined;

  let result: unknown;
  switch (msg.method) {
    case 'initialize': {
      const requested = typeof msg.params?.protocolVersion === 'string' ? msg.params.protocolVersion : undefined;
      result = {
        protocolVersion: requested ?? PROTOCOL_VERSION,
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions:
          'Trips Enjoy product documentation: business requirements (BRD), software requirements (SRS, 201 FRs), data model (ERD) and user flows. Search first, then fetch pages as Markdown.',
      };
      break;
    }
    case 'ping':
      result = {};
      break;
    case 'tools/list':
      result = { tools: TOOLS };
      break;
    case 'tools/call': {
      const name = (msg.params?.name as string) ?? '';
      const args = (msg.params?.arguments as Record<string, unknown>) ?? {};
      result = await callTool(name, args);
      break;
    }
    default:
      if (isNotification) return null;
      return rpcError(msg.id ?? null, -32601, `Method not found: ${msg.method}`);
  }

  if (isNotification) return null;
  return ok(msg.id ?? null, result);
}

export const Route = createFileRoute('/api/mcp')({
  server: {
    handlers: {
      OPTIONS: () => new Response(null, { status: 204, headers: CORS }),
      GET: () =>
        Response.json(
          {
            server: SERVER_INFO,
            transport: 'streamable-http (stateless)',
            endpoint: 'POST /api/mcp — JSON-RPC 2.0 (initialize, ping, tools/list, tools/call)',
          },
          { headers: CORS },
        ),
      POST: async ({ request }) => {
        let body: unknown;
        try {
          body = await request.json();
        } catch {
          return rpcError(null, -32700, 'Parse error');
        }
        try {
          const response = await handleRpc(body);
          return response ?? new Response(null, { status: 202, headers: CORS });
        } catch (error) {
          return rpcError(null, -32603, `Internal error: ${String(error)}`);
        }
      },
    },
  },
});
