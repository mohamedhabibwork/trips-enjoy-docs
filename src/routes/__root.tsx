import { createRootRoute, HeadContent, Outlet, Scripts, useLocation } from '@tanstack/react-router';
import appCss from '@/styles/app.css?url';
import { RootProvider } from 'fumadocs-ui/provider/tanstack';
import { i18n } from '@/lib/i18n';
import { i18nProvider } from 'fumadocs-ui/i18n';
import { translations } from '@/lib/layout.shared';

export const Route = createRootRoute({
  head: () => ({
    meta: [
      {
        charSet: 'utf-8',
      },
      {
        name: 'viewport',
        content: 'width=device-width, initial-scale=1',
      },
      {
        title: 'Trips Enjoy Docs',
      },
    ],
    links: [
      { rel: 'stylesheet', href: appCss },
      { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
    ],
  }),
  component: RootComponent,
});

function RootComponent() {
  // unmatched /ar/… URLs (404s) still get the RTL shell — useParams needs a
  // matched route, which the not-found page doesn't have
  const pathname = useLocation().pathname;
  const lang = pathname === '/ar' || pathname.startsWith('/ar/') ? 'ar' : i18n.defaultLanguage;

  return (
    <html lang={lang} dir={['ar'].includes(lang) ? 'rtl' : 'ltr'} suppressHydrationWarning>
      <head>
        <HeadContent />
      </head>
      <body className="flex flex-col min-h-screen">
        <RootProvider i18n={i18nProvider(translations, lang)}>
          <Outlet />
        </RootProvider>
        <Scripts />
      </body>
    </html>
  );
}
