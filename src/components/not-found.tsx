import { baseOptions } from '@/lib/layout.shared';
import { i18n } from '@/lib/i18n';
import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { DefaultNotFound } from 'fumadocs-ui/layouts/home/not-found';

export function NotFound() {
  return (
    <HomeLayout {...baseOptions(i18n.defaultLanguage)}>
      <DefaultNotFound />
    </HomeLayout>
  );
}
