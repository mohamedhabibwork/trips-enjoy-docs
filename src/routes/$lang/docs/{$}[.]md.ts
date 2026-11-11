import { createFileRoute, notFound } from '@tanstack/react-router';
import { docs, source } from '@/lib/source';
import { decodeMarkdownUrl } from '@/lib/shared';

export const Route = createFileRoute('/$lang/docs/{$}.md')({
  server: {
    handlers: {
      GET: async ({ params }) => {
        const slugs = decodeMarkdownUrl(params._splat?.split('/') ?? []);
        const page = source.getPage(slugs, params.lang);
        if (!page) throw notFound();
        const entry = docs.getPage(page.path);
        if (!entry) throw notFound();
        await entry.preload();

        const markdown = await entry.getText('processed');
        return new Response(`# ${entry.title} (${page.url})\n\n${markdown}`, {
          headers: {
            'Content-Type': 'text/markdown',
          },
        });
      },
    },
  },
});
