import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import type { DocsLayoutProps } from 'fumadocs-ui/layouts/docs';
import Link from 'fumadocs-core/link';
import { uiTranslations } from 'fumadocs-ui/i18n';
import { GitFork, Layers, Lightbulb, ListTodo, Plane } from 'lucide-react';
import { appName, docsRoute, gitConfig } from './shared';
import { landing } from './landing.content';
import { i18n } from './i18n';

export const translations = i18n
  .translations()
  .extend(uiTranslations())
  .add({
    en: {
      displayName: 'English',
    },
    ar: {
      displayName: 'Arabic',
    },
  });

/** Locale-aware labels for layout chrome (navbar, sidebar, footer). */
const layoutLabels = {
  en: {
    docs: 'Docs',
    system: 'End-to-End',
    mindmaps: 'Mindmaps',
    plan: 'Delivery Plan',
    recommendations: 'Recommendations',
    github: 'GitHub',
    copyright: 'Documentation for the Trips Enjoy platform.',
  },
  ar: {
    docs: 'الوثائق',
    system: 'النظام الشامل',
    mindmaps: 'الخرائط الذهنية',
    plan: 'خطة التسليم',
    recommendations: 'التوصيات',
    github: 'GitHub',
    copyright: 'وثائق منصة Trips Enjoy.',
  },
} as const;

function labels(locale: string) {
  return (
    layoutLabels[locale as keyof typeof layoutLabels] ?? layoutLabels.en
  );
}

const githubUrl = `https://github.com/${gitConfig.user}/${gitConfig.repo}`;
const docsUrl = (locale: string) => `/${locale}${docsRoute}`;
const year = new Date().getFullYear();

function NavTitle() {
  return (
    <>
      <span className="inline-flex size-7 shrink-0 items-center justify-center rounded-md bg-fd-primary text-fd-primary-foreground">
        <Plane className="size-4" />
      </span>
      <span>{appName}</span>
    </>
  );
}

export function baseOptions(locale: string): BaseLayoutProps {
  const l = labels(locale);
  const root = docsUrl(locale);
  return {
    nav: {
      title: <NavTitle />,
      url: `/${locale}`,
      // NOTE: `transparentMode: 'top'` triggers a hydration mismatch in
      // fumadocs' TOC popover when the browser restores scroll (16.18.4).
      // Solid navbar until fixed upstream.
    },
    links: [
      {
        type: 'main',
        text: l.docs,
        url: root,
        active: 'url',
      },
      {
        type: 'main',
        text: l.system,
        url: `${root}/system`,
        active: 'nested-url',
      },
      {
        type: 'main',
        text: l.mindmaps,
        url: `${root}/system/mindmaps`,
        active: 'url',
      },
      {
        type: 'main',
        text: l.plan,
        url: `${root}/plan`,
        active: 'nested-url',
      },
      {
        type: 'main',
        text: l.recommendations,
        url: `${root}/recommendations`,
        active: 'nested-url',
      },
    ],
    githubUrl,
  };
}

/** DocsLayout options: base options plus sidebar chrome with quick-access shortcuts. */
export function docsLayoutOptions(
  locale: string,
): Omit<DocsLayoutProps, 'tree' | 'children'> {
  const l = labels(locale);
  const root = docsUrl(locale);

  return {
    ...baseOptions(locale),
    sidebar: {
      banner: (
        <div className="flex flex-col gap-2">
          <p className="rounded-lg border border-fd-border bg-fd-secondary px-3 py-2 text-xs text-fd-secondary-foreground">
            {landing(locale).edition}
          </p>
          <div className="grid grid-cols-2 gap-1.5 text-[11px] font-medium">
            <Link
              href={`${root}/system`}
              className="inline-flex items-center gap-1.5 rounded-md border border-fd-border bg-fd-card px-2 py-1.5 text-fd-foreground transition-colors hover:border-fd-primary/50 hover:bg-fd-accent"
            >
              <Layers className="size-3.5 shrink-0 text-fd-primary" />
              <span className="truncate">{l.system}</span>
            </Link>
            <Link
              href={`${root}/system/mindmaps`}
              className="inline-flex items-center gap-1.5 rounded-md border border-fd-border bg-fd-card px-2 py-1.5 text-fd-foreground transition-colors hover:border-fd-primary/50 hover:bg-fd-accent"
            >
              <GitFork className="size-3.5 shrink-0 text-fd-primary" />
              <span className="truncate">{l.mindmaps}</span>
            </Link>
            <Link
              href={`${root}/plan`}
              className="inline-flex items-center gap-1.5 rounded-md border border-fd-border bg-fd-card px-2 py-1.5 text-fd-foreground transition-colors hover:border-fd-primary/50 hover:bg-fd-accent"
            >
              <ListTodo className="size-3.5 shrink-0 text-fd-primary" />
              <span className="truncate">{l.plan}</span>
            </Link>
            <Link
              href={`${root}/recommendations`}
              className="inline-flex items-center gap-1.5 rounded-md border border-fd-border bg-fd-card px-2 py-1.5 text-fd-foreground transition-colors hover:border-fd-primary/50 hover:bg-fd-accent"
            >
              <Lightbulb className="size-3.5 shrink-0 text-fd-primary" />
              <span className="truncate">{l.recommendations}</span>
            </Link>
          </div>
        </div>
      ),
    },
  };
}

export function SiteFooter({ locale }: { locale: string }) {
  const l = labels(locale);
  const root = docsUrl(locale);

  return (
    <footer className="border-t border-fd-border">
      <div className="mx-auto flex w-full max-w-4xl flex-col items-center gap-3 px-6 py-8 text-sm text-fd-muted-foreground sm:flex-row sm:justify-between">
        <p>
          © {year} {appName}. {l.copyright}
        </p>
        <nav className="flex flex-wrap items-center gap-4" aria-label="Footer">
          <Link
            href={root}
            className="transition-colors hover:text-fd-foreground"
          >
            {l.docs}
          </Link>
          <Link
            href={`${root}/system`}
            className="transition-colors hover:text-fd-foreground"
          >
            {l.system}
          </Link>
          <Link
            href={`${root}/plan`}
            className="transition-colors hover:text-fd-foreground"
          >
            {l.plan}
          </Link>
          <Link
            href={`${root}/recommendations`}
            className="transition-colors hover:text-fd-foreground"
          >
            {l.recommendations}
          </Link>
          <a
            href={githubUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="transition-colors hover:text-fd-foreground"
          >
            {l.github}
          </a>
        </nav>
      </div>
    </footer>
  );
}
