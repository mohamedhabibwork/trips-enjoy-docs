import react from '@vitejs/plugin-react';
import { tanstackStart } from '@tanstack/react-start/plugin/vite';
import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';
import { fumadocsMdx } from 'fumadocs-mdx/vite';
import { remarkMdxMermaid } from 'fumadocs-core/mdx-plugins';
import { nitro } from 'nitro/vite';

export default defineConfig({
  server: {
    port: 3000,
  },
  plugins: [
    fumadocsMdx({
      // extra global options, shallow-merged into the MDX config:
      // ```mermaid fences become <Mermaid chart="..." /> elements
      globalOptions: {
        mdxOptions: {
          remarkPlugins: [remarkMdxMermaid],
        },
      },
    }),
    tailwindcss(),
    tanstackStart({
      pages: [
        { path: '/en' },
        { path: '/en/docs' },
        { path: '/ar' },
        { path: '/ar/docs' },
      ],
      prerender: {
        enabled: true,
        crawlLinks: true,
        concurrency: 4,
        failOnError: true,
        // never freeze API endpoints, and skip duplicate #hash / ?query crawl
        // variants so concurrent workers never race writing the same index.html
        filter: (page) =>
          !page.path.startsWith('/api') &&
          !page.path.includes('#') &&
          !page.path.includes('?'),
      },
    }),
    react(),
    // please see https://tanstack.com/start/latest/docs/framework/react/guide/hosting#nitro for guides on hosting
    nitro({
      // 'vercel' everywhere; the Dockerfile overrides to 'node-server' via NITRO_PRESET
      preset: process.env.NITRO_PRESET ?? 'vercel',
    }),
  ],
  resolve: {
    tsconfigPaths: true,
    alias: {
      tslib: 'tslib/tslib.es6.js',
    },
  },
});
