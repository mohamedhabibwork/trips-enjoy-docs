import { createFileRoute } from '@tanstack/react-router';
import { generateOGImage } from 'fumadocs-ui/og/takumi';
import { source } from '@/lib/source';
import { appName } from '@/lib/shared';

// OG images are requested with the locale-prefixed URL (/ar/og/docs/…) —
// createGetUrl prefixes the locale for non-default languages.
export const Route = createFileRoute('/$lang/og/docs/$')({
  server: {
    handlers: {
      GET: ({ params }) => {
        const slugs = (params._splat?.split('/') ?? []).filter((v) => v.length > 0).slice(0, -1);
        const page = source.getPage(slugs, params.lang);
        if (!page) return new Response(undefined, { status: 404 });

        return generateOGImage({
          title: page.data.title,
          description: page.data.description,
          site: appName,
          format: 'webp',
        });
      },
    },
  },
});
