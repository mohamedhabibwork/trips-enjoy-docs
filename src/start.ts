import { createMiddleware, createCsrfMiddleware, createStart } from '@tanstack/react-start';
import { isMarkdownPreferred } from 'fumadocs-core/negotiation';
import { redirect } from '@tanstack/react-router';
import { docsRoute, getPageMarkdownUrl } from '@/lib/shared';
import { i18n } from '@/lib/i18n';

const csrfMiddleware = createCsrfMiddleware({
  filter: (ctx) => ctx.handlerType === 'serverFn',
});

// doc URLs are locale-prefixed (`/en/docs/...`, `/ar/docs/...`) — strip the
// locale before matching against the docs base route
const localePrefix = new RegExp(`^/(${i18n.languages.join('|')})(?=/|$)`);

const llmMiddleware = createMiddleware().server(({ next, request }) => {
  const url = new URL(request.url);
  const docsPath = url.pathname.replace(localePrefix, '');

  if (
    docsPath.startsWith(docsRoute) &&
    !docsPath.endsWith('.md') &&
    isMarkdownPreferred(request)
  ) {
    const slugs = docsPath
      .slice(docsRoute.length)
      .split('/')
      .filter((v) => v.length > 0);
    url.pathname = getPageMarkdownUrl({ slugs }).url;

    // this URL has two representations, selected by `Accept`
    throw redirect({ href: url.href, headers: { Vary: 'Accept' } });
  }

  return next();
});

export const startInstance = createStart(() => {
  return {
    requestMiddleware: [csrfMiddleware, llmMiddleware],
  };
});
