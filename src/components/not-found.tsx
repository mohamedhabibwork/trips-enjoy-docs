import { baseOptions, SiteFooter } from '@/lib/layout.shared';
import { useLocation } from '@tanstack/react-router';
import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { DefaultNotFound } from 'fumadocs-ui/layouts/home/not-found';

export function NotFound() {
  const { pathname } = useLocation();
  const lang = pathname.startsWith('/ar/') ? 'ar' : 'en';

  return (
    <HomeLayout {...baseOptions(lang)}>
      <DefaultNotFound />
      <SiteFooter locale={lang} />
    </HomeLayout>
  );
}
