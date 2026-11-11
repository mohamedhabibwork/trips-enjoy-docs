import { useLocation } from '@tanstack/react-router';
import { Activity, Database, Radio, Users } from 'lucide-react';
import type { ReactNode } from 'react';
import { TraceBar, type TraceBarProps } from './trace-bar';

export interface SystemStageProps {
  stageNumber: number;
  id: string;
  title: string;
  phaseIntroduced: string;
  actors: string[];
  stateMachines?: string[];
  tablesWritten?: string[];
  eventsEmitted?: string[];
  trace: TraceBarProps;
  children: ReactNode;
}

export function SystemStage({
  stageNumber,
  id,
  title,
  phaseIntroduced,
  actors,
  stateMachines = [],
  tablesWritten = [],
  eventsEmitted = [],
  trace,
  children,
}: SystemStageProps) {
  const { pathname } = useLocation();
  const lang: 'en' | 'ar' = pathname.startsWith('/ar/') ? 'ar' : 'en';
  const numStr = String(stageNumber).padStart(2, '0');

  return (
    <section
      id={id.toLowerCase()}
      aria-label={`${id} — ${title}`}
      className="my-6 scroll-mt-24 overflow-hidden rounded-2xl border border-fd-border bg-fd-card text-fd-card-foreground shadow-xs"
    >
      <div className="not-prose flex flex-wrap items-center justify-between gap-2 border-b border-fd-border bg-fd-secondary/60 px-4 py-3">
        <div className="flex flex-wrap items-center gap-2.5">
          <span className="inline-flex items-center justify-center rounded-lg bg-fd-primary px-2.5 py-1 font-mono text-xs font-bold text-fd-primary-foreground">
            {lang === 'ar' ? `المرحلة ${numStr}` : `STAGE ${numStr}`}
          </span>
          <span className="font-mono text-xs font-semibold text-fd-muted-foreground">{id}</span>
          <span className="text-sm font-semibold text-fd-foreground">{title}</span>
        </div>
        <span className="rounded-full border border-teal-500/35 bg-teal-500/10 px-2.5 py-0.5 text-xs font-semibold text-teal-700 dark:text-teal-300">
          {lang === 'ar' ? `يدخل في ${phaseIntroduced}` : `Introduced ${phaseIntroduced}`}
        </span>
      </div>

      <div className="not-prose grid grid-cols-1 gap-2.5 border-b border-fd-border/70 bg-fd-background/60 px-4 py-3 text-xs sm:grid-cols-2">
        <div className="flex flex-wrap items-start gap-1.5">
          <span className="inline-flex items-center gap-1 font-semibold text-fd-muted-foreground">
            <Users className="size-3.5" />
            {lang === 'ar' ? 'الأطراف الفاعلة:' : 'Actors:'}
          </span>
          {actors.map((a) => (
            <span
              key={a}
              className="rounded bg-fd-secondary px-1.5 py-0.5 text-[11px] font-medium text-fd-secondary-foreground"
            >
              {a}
            </span>
          ))}
        </div>

        {stateMachines.length > 0 ? (
          <div className="flex flex-wrap items-start gap-1.5">
            <span className="inline-flex items-center gap-1 font-semibold text-fd-muted-foreground">
              <Activity className="size-3.5" />
              {lang === 'ar' ? 'آلات الحالة:' : 'State Machines:'}
            </span>
            {stateMachines.map((sm) => (
              <span
                key={sm}
                className="rounded border border-indigo-500/30 bg-indigo-500/10 px-1.5 py-0.5 text-[11px] font-medium text-indigo-700 dark:text-indigo-300"
              >
                {sm}
              </span>
            ))}
          </div>
        ) : null}

        {tablesWritten.length > 0 ? (
          <div className="flex flex-wrap items-start gap-1.5">
            <span className="inline-flex items-center gap-1 font-semibold text-fd-muted-foreground">
              <Database className="size-3.5" />
              {lang === 'ar' ? 'الجداول المتأثرة:' : 'Tables Written:'}
            </span>
            {tablesWritten.map((tbl) => (
              <code
                key={tbl}
                className="rounded border border-fd-border bg-fd-card px-1.5 py-0.5 font-mono text-[11px] text-fd-foreground"
              >
                {tbl}
              </code>
            ))}
          </div>
        ) : null}

        {eventsEmitted.length > 0 ? (
          <div className="flex flex-wrap items-start gap-1.5">
            <span className="inline-flex items-center gap-1 font-semibold text-fd-muted-foreground">
              <Radio className="size-3.5" />
              {lang === 'ar' ? 'الأحداث والقنوات:' : 'Outbox & Realtime:'}
            </span>
            {eventsEmitted.map((ev) => (
              <code
                key={ev}
                className="rounded bg-fd-secondary px-1.5 py-0.5 font-mono text-[11px] text-fd-secondary-foreground"
              >
                {ev}
              </code>
            ))}
          </div>
        ) : null}
      </div>

      <div className="px-4 py-3.5">
        <div className="prose prose-sm max-w-none dark:prose-invert prose-p:my-2 prose-ul:my-2 prose-li:my-0.5">
          {children}
        </div>
        <TraceBar {...trace} />
      </div>
    </section>
  );
}
