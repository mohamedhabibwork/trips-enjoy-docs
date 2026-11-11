import { source, docs } from './source';
import { i18n } from './i18n';
import { createI18nSearchAPI } from 'fumadocs-core/search/server';

/**
 * Shared full-text search index for the site search API and the MCP server.
 * A single instance keeps the (in-memory) index built once per server.
 *
 * Built explicitly per locale: the loader's default `createFromSource` walks
 * `getPages()` without a language, which only covers the default locale.
 */
const api = createI18nSearchAPI('advanced', {
  i18n: {
    defaultLanguage: i18n.defaultLanguage,
    languages: [...i18n.languages],
  },
  indexes: async () => {
    const langs = [...i18n.languages] as ('en' | 'ar')[];
    const all = await Promise.all(
      langs.map(async (lang) => {
        const pages = source.getPages(lang);
        return Promise.all(
          pages.map(async (page) => {
            const entry = docs.getPage(page.path);
            const structuredData = await entry?.structuredData();
            return {
              title: page.data.title ?? page.path,
              description: page.data.description,
              url: page.url,
              id: page.url,
              locale: lang,
              structuredData: structuredData ?? { headings: [], contents: [] },
            };
          }),
        );
      }),
    );
    return all.flat();
  },
});

export const searchServer = {
  search: (query: string, options: { locale?: string | null; limit?: number } = {}) =>
    api.search(query, options),

  /** Full SearchServer contract (fumadocs-core/mcp registerSearchTool). */
  export: (...args: Parameters<typeof api.export>) => api.export(...args),

  /** GET handler for /api/search?query=…&locale=… — the endpoint's own GET
   * ignores the locale param, so parse it here and delegate. */
  async GET(request: Request): Promise<Response> {
    const u = new URL(request.url);
    const query = u.searchParams.get('query') ?? '';
    const locale = u.searchParams.get('locale') ?? undefined;
    const limitParam = u.searchParams.get('limit');
    const limit = limitParam ? Number(limitParam) : undefined;
    const hits = await api.search(query, { locale, limit });
    return Response.json(hits);
  },
};
