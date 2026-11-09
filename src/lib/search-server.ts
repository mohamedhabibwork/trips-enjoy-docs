import { source } from './source';
import { createFromSource } from 'fumadocs-core/search/server';

/**
 * Shared full-text search index for the site search API and the MCP server.
 * A single instance keeps the (in-memory) index built once per server.
 */
export const searchServer = createFromSource(source, {
  // https://docs.orama.com/docs/orama-js/supported-languages
  language: 'english',
});
