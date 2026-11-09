import { createFileRoute, Link } from '@tanstack/react-router';
import { ArrowRight, Bot, ExternalLink } from 'lucide-react';
import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { baseOptions } from '@/lib/layout.shared';
import { gitConfig } from '@/lib/shared';
import { hero, idPrefixes, roles, sections, stats } from '@/lib/landing.content';

export const Route = createFileRoute('/$lang/')({
  component: Home,
});

function DocsLink({
  lang,
  splat,
  className,
  children,
}: {
  lang: string;
  splat: string;
  className: string;
  children: React.ReactNode;
}) {
  return (
    <Link
      to="/$lang/docs/$"
      params={{ lang, _splat: splat }}
      className={className}
    >
      {children}
    </Link>
  );
}

function SectionHeading({
  eyebrow,
  title,
  description,
}: {
  eyebrow: string;
  title: string;
  description?: string;
}) {
  return (
    <div className="mb-8 max-w-2xl">
      <p className="text-xs font-semibold uppercase tracking-widest text-fd-primary">
        {eyebrow}
      </p>
      <h2 className="mt-2 text-2xl font-semibold tracking-tight text-fd-foreground">
        {title}
      </h2>
      {description ? (
        <p className="mt-2 text-sm leading-relaxed text-fd-muted-foreground">
          {description}
        </p>
      ) : null}
    </div>
  );
}

function Home() {
  const { lang } = Route.useParams();

  return (
    <HomeLayout {...baseOptions(lang)}>
      <main className="flex flex-1 flex-col">
        {/* Hero */}
        <section className="mx-auto w-full max-w-4xl px-6 pt-20 pb-16 text-center">
          <span className="inline-flex items-center gap-2 rounded-full border border-fd-border bg-fd-card px-3 py-1 text-xs font-medium text-fd-muted-foreground">
            <span className="size-1.5 rounded-full bg-fd-primary" />
            {hero.badge}
          </span>
          <h1 className="mt-6 text-4xl font-semibold tracking-tight text-balance text-fd-foreground sm:text-5xl">
            {hero.tagline}
          </h1>
          <p className="mx-auto mt-5 max-w-2xl text-pretty text-base leading-relaxed text-fd-muted-foreground sm:text-lg">
            {hero.subtitle}
          </p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
            <DocsLink
              lang={lang}
              splat=""
              className="inline-flex items-center gap-2 rounded-lg bg-fd-primary px-4 py-2 text-sm font-medium text-fd-primary-foreground transition-colors hover:bg-fd-primary/90"
            >
              Open the docs
              <ArrowRight className="size-4 rtl:rotate-180" />
            </DocsLink>
            <DocsLink
              lang={lang}
              splat="brd"
              className="inline-flex items-center gap-2 rounded-lg border border-fd-border bg-fd-card px-4 py-2 text-sm font-medium text-fd-foreground transition-colors hover:bg-fd-accent hover:text-fd-accent-foreground"
            >
              Start with the business case
            </DocsLink>
          </div>
        </section>

        {/* Stats band */}
        <section className="border-y border-fd-border bg-fd-card/50">
          <div className="mx-auto grid w-full max-w-5xl grid-cols-2 gap-y-8 px-6 py-10 sm:grid-cols-3 lg:grid-cols-5">
            {stats.map((stat) => (
              <div key={stat.label} className="text-center">
                <div className="text-3xl font-semibold tracking-tight text-fd-foreground">
                  {stat.value}
                </div>
                <div className="mt-1.5 text-xs font-medium text-fd-muted-foreground">
                  {stat.label}
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* Documentation set */}
        <section className="mx-auto w-full max-w-5xl px-6 py-16">
          <SectionHeading
            eyebrow="The set"
            title="Four documents, one vocabulary"
            description="Every document cites the others by stable ID, so a business rule traces down to a requirement, a table and a flow."
          />
          <div className="grid gap-4 sm:grid-cols-2">
            {sections.map((section) => (
              <DocsLink
                key={section.slug}
                lang={lang}
                splat={section.slug}
                className="group rounded-xl border border-fd-border bg-fd-card p-6 transition-colors hover:border-fd-primary/50"
              >
                <div className="flex items-start gap-4">
                  <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-fd-primary/10 text-fd-primary">
                    <section.icon className="size-5" />
                  </span>
                  <div className="min-w-0">
                    <h3 className="inline-flex items-center gap-1.5 font-semibold text-fd-foreground">
                      {section.title}
                      <ArrowRight className="size-3.5 text-fd-primary opacity-0 transition-opacity group-hover:opacity-100 rtl:rotate-180" />
                    </h3>
                    <p className="mt-1.5 text-sm leading-relaxed text-fd-muted-foreground">
                      {section.description}
                    </p>
                    <p className="mt-3 font-mono text-xs text-fd-muted-foreground/80">
                      {section.meta}
                    </p>
                  </div>
                </div>
              </DocsLink>
            ))}
          </div>
        </section>

        {/* Roles */}
        <section className="mx-auto w-full max-w-5xl px-6 pb-16">
          <SectionHeading
            eyebrow="User flows"
            title="Follow a role"
            description="Each app has its own flow file: registration, approval, and everything the role can do once active."
          />
          <div className="flex flex-wrap gap-2.5">
            {roles.map((role) => (
              <DocsLink
                key={role.slug}
                lang={lang}
                splat={`user-flows/${role.slug}`}
                className="inline-flex items-center gap-2 rounded-full border border-fd-border bg-fd-card px-4 py-2 text-sm font-medium text-fd-foreground transition-colors hover:border-fd-primary/50 hover:bg-fd-accent hover:text-fd-accent-foreground"
              >
                <role.icon className="size-4 text-fd-primary" />
                {role.label}
              </DocsLink>
            ))}
          </div>
        </section>

        {/* Reading guide */}
        <section className="mx-auto w-full max-w-5xl px-6 pb-16">
          <SectionHeading
            eyebrow="Conventions"
            title="How to read it"
            description="Requirements cite each other by ID prefix. Every SRS page lists its MoSCoW priority and the BRD IDs it traces to; [OPEN] items follow the open-decision process in the BRD."
          />
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {idPrefixes.map((item) => (
              <div
                key={item.prefix}
                className="flex items-center gap-3 rounded-lg border border-fd-border bg-fd-card p-4"
              >
                <code className="rounded bg-fd-secondary px-1.5 py-0.5 font-mono text-xs font-semibold text-fd-secondary-foreground">
                  {item.prefix}
                </code>
                <span className="text-sm text-fd-muted-foreground">
                  {item.meaning}
                </span>
              </div>
            ))}
          </div>
        </section>

        {/* AI + repo footer */}
        <section className="border-t border-fd-border bg-fd-card/50">
          <div className="mx-auto flex w-full max-w-5xl flex-col items-start justify-between gap-4 px-6 py-10 sm:flex-row sm:items-center">
            <div className="flex items-start gap-3">
              <Bot className="mt-0.5 size-5 shrink-0 text-fd-primary" />
              <div>
                <p className="text-sm font-semibold text-fd-foreground">
                  Built for AI agents too
                </p>
                <p className="mt-0.5 text-sm text-fd-muted-foreground">
                  The whole set is served as plain text for LLM consumption.
                </p>
              </div>
            </div>
            <div className="flex flex-wrap gap-3">
              <a
                href="/llms.txt"
                className="inline-flex items-center gap-2 rounded-lg border border-fd-border bg-fd-card px-3.5 py-2 text-sm font-medium text-fd-foreground transition-colors hover:bg-fd-accent hover:text-fd-accent-foreground"
              >
                llms.txt
              </a>
              <a
                href="/llms-full.txt"
                className="inline-flex items-center gap-2 rounded-lg border border-fd-border bg-fd-card px-3.5 py-2 text-sm font-medium text-fd-foreground transition-colors hover:bg-fd-accent hover:text-fd-accent-foreground"
              >
                llms-full.txt
              </a>
              <a
                href={`https://github.com/${gitConfig.user}/${gitConfig.repo}`}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-2 rounded-lg border border-fd-border bg-fd-card px-3.5 py-2 text-sm font-medium text-fd-foreground transition-colors hover:bg-fd-accent hover:text-fd-accent-foreground"
              >
                <ExternalLink className="size-4" />
                GitHub
              </a>
            </div>
          </div>
        </section>
      </main>
    </HomeLayout>
  );
}
