import { useLocation } from '@tanstack/react-router';
import { CheckSquare, Database, Layers } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { TraceBar, type TraceBarProps } from './trace-bar';

export type TaskPriority = 'Must' | 'Should' | 'Could';

export interface TaskCardProps {
  id: string;
  title: string;
  phase: 'P1' | 'P2' | 'P3' | 'P4' | 'P5' | 'P6';
  module: string;
  priority: TaskPriority;
  kind?: 'functional' | 'platform';
  surfaces?: string[];
  tables?: string[];
  trace: TraceBarProps;
  children: ReactNode;
}

const priorityStyles: Record<TaskPriority, { badge: string; labelAr: string }> = {
  Must: {
    badge: 'border-rose-500/35 bg-rose-500/10 text-rose-700 dark:text-rose-300',
    labelAr: 'إلزامي (Must)',
  },
  Should: {
    badge: 'border-amber-500/35 bg-amber-500/10 text-amber-700 dark:text-amber-300',
    labelAr: 'مهم (Should)',
  },
  Could: {
    badge: 'border-slate-500/35 bg-slate-500/10 text-slate-700 dark:text-slate-300',
    labelAr: 'تحسيني (Could)',
  },
};

export function TaskCard({
  id,
  title,
  phase,
  module,
  priority,
  kind = 'functional',
  surfaces = [],
  tables = [],
  trace,
  children,
}: TaskCardProps) {
  const { pathname } = useLocation();
  const lang: 'en' | 'ar' = pathname.startsWith('/ar/') ? 'ar' : 'en';
  const pStyle = priorityStyles[priority] ?? priorityStyles.Must;

  return (
    <section
      id={id.toLowerCase()}
      aria-label={`${id} — ${title}`}
      className="my-5 scroll-mt-24 overflow-hidden rounded-2xl border border-fd-border bg-fd-card text-fd-card-foreground shadow-xs"
    >
      <div className="not-prose flex flex-wrap items-center justify-between gap-2 border-b border-fd-border bg-fd-secondary/50 px-4 py-2.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="inline-flex items-center gap-1.5 rounded-md bg-fd-primary/10 px-2 py-0.5 font-mono text-xs font-semibold text-fd-primary">
            <CheckSquare className="size-3.5 shrink-0" />
            {id}
          </span>
          <span className="rounded-md border border-fd-border bg-fd-background px-2 py-0.5 text-xs font-medium text-fd-muted-foreground">
            {phase}
          </span>
          <span className="rounded-md border border-fd-border bg-fd-background px-2 py-0.5 font-mono text-xs font-medium text-fd-foreground">
            {module}
          </span>
          {kind === 'platform' ? (
            <span className="rounded-md border border-indigo-500/30 bg-indigo-500/10 px-2 py-0.5 text-xs font-medium text-indigo-700 dark:text-indigo-300">
              {lang === 'ar' ? 'بنية المنصة المشتركة' : 'Platform Foundation'}
            </span>
          ) : null}
        </div>
        <span
          className={cn(
            'rounded-full border px-2.5 py-0.5 text-xs font-semibold',
            pStyle.badge,
          )}
        >
          {lang === 'ar' ? pStyle.labelAr : priority}
        </span>
      </div>

      {(surfaces.length > 0 || tables.length > 0) && (
        <div className="not-prose flex flex-wrap items-center gap-x-4 gap-y-1.5 border-b border-fd-border/70 bg-fd-background/60 px-4 py-2 text-xs">
          {surfaces.length > 0 ? (
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="inline-flex items-center gap-1 font-medium text-fd-muted-foreground">
                <Layers className="size-3.5" />
                {lang === 'ar' ? 'الواجهات:' : 'Surfaces:'}
              </span>
              {surfaces.map((s) => (
                <span
                  key={s}
                  className="rounded bg-fd-secondary px-1.5 py-0.5 text-[11px] text-fd-secondary-foreground"
                >
                  {s}
                </span>
              ))}
            </div>
          ) : null}
          {tables.length > 0 ? (
            <div className="flex flex-wrap items-center gap-1.5">
              <span className="inline-flex items-center gap-1 font-medium text-fd-muted-foreground">
                <Database className="size-3.5" />
                {lang === 'ar' ? 'الجداول:' : 'Tables:'}
              </span>
              {tables.map((tbl) => (
                <code
                  key={tbl}
                  className="rounded border border-fd-border bg-fd-card px-1.5 py-0.5 font-mono text-[11px] text-fd-foreground"
                >
                  {tbl}
                </code>
              ))}
            </div>
          ) : null}
        </div>
      )}

      <div className="px-4 py-3.5">
        <div className="prose prose-sm max-w-none dark:prose-invert prose-p:my-2 prose-ul:my-2 prose-li:my-0.5">
          {children}
        </div>
        <TraceBar {...trace} />
      </div>
    </section>
  );
}
