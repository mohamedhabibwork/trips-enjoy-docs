import { useLocation } from '@tanstack/react-router';
import { GitFork } from 'lucide-react';
import type { ReactNode } from 'react';
import { TraceBar, type TraceBarProps } from './trace-bar';

export interface MindmapBlockProps {
  title: string;
  caption: string;
  trace?: TraceBarProps;
  children: ReactNode;
}

export function MindmapBlock({ title, caption, trace, children }: MindmapBlockProps) {
  const { pathname } = useLocation();
  const lang: 'en' | 'ar' = pathname.startsWith('/ar/') ? 'ar' : 'en';

  return (
    <figure className="my-6 overflow-hidden rounded-2xl border border-fd-border bg-fd-card text-fd-card-foreground shadow-xs">
      <figcaption className="not-prose flex flex-col gap-1 border-b border-fd-border bg-fd-secondary/50 px-4 py-3">
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1 rounded-md border border-teal-500/35 bg-teal-500/10 px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-teal-700 dark:text-teal-300">
            <GitFork className="size-3 shrink-0" />
            {lang === 'ar' ? 'خريطة ذهنية للنظام' : 'Visual Mindmap'}
          </span>
          <span className="text-sm font-semibold text-fd-foreground">{title}</span>
        </div>
        <p className="text-xs leading-relaxed text-fd-muted-foreground">{caption}</p>
      </figcaption>

      <div className="px-4 py-3">
        <div className="prose prose-sm max-w-none dark:prose-invert">{children}</div>
        {trace ? <TraceBar {...trace} compact /> : null}
      </div>
    </figure>
  );
}
