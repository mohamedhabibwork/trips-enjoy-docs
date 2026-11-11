import { useLocation } from '@tanstack/react-router';
import { CalendarClock, Lightbulb, ShieldAlert, UserCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { TraceBar, type TraceBarProps } from './trace-bar';

export type RecommendationCategory =
  | 'open-decision'
  | 'architecture'
  | 'security'
  | 'operations'
  | 'payments-tax'
  | 'ux-rtl';

export type RecommendationStatus =
  | 'Open'
  | 'Proposed'
  | 'Decided — Guidance'
  | 'Strategic Advisory';

export type RecommendationImpact = 'Critical' | 'High' | 'Medium';

export interface RecommendationCardProps {
  id: string;
  title: string;
  category: RecommendationCategory;
  currentStatus: RecommendationStatus;
  targetPhase: string;
  owner: string;
  impact: RecommendationImpact;
  recommendedResolution: string;
  trace: TraceBarProps;
  children: ReactNode;
}

const categoryLabels: Record<RecommendationCategory, { en: string; ar: string }> = {
  'open-decision': { en: 'Open Decision Resolution', ar: 'حسم قرار مفتوح' },
  architecture: { en: 'Architecture & Data', ar: 'المعمارية والبيانات' },
  security: { en: 'Security & Compliance', ar: 'الأمان والامتثال' },
  operations: { en: 'Operations & Offline', ar: 'التشغيل والعمل دون اتصال' },
  'payments-tax': { en: 'Payments, Ledger & Tax', ar: 'المدفوعات والدفتر والضرائب' },
  'ux-rtl': { en: 'UX, Localization & A11y', ar: 'تجربة المستخدم والتعريب والوصول' },
};

const statusLabels: Record<RecommendationStatus, { en: string; ar: string; tone: string }> = {
  Open: {
    en: 'Open → Recommended Resolution',
    ar: 'مفتوح ← قرار موصى به',
    tone: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  },
  Proposed: {
    en: 'Proposed → Confirm & Lock',
    ar: 'مقترح ← للتأكيد والاعتماد',
    tone: 'border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300',
  },
  'Decided — Guidance': {
    en: 'Decided — Implementation Guidance',
    ar: 'محسوم — إرشادات التنفيذ',
    tone: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  },
  'Strategic Advisory': {
    en: 'Engineering & Product Advisory',
    ar: 'توصية هندسية وتشغيلية',
    tone: 'border-violet-500/40 bg-violet-500/10 text-violet-700 dark:text-violet-300',
  },
};

const impactStyles: Record<RecommendationImpact, { en: string; ar: string; tone: string }> = {
  Critical: {
    en: 'Critical Impact',
    ar: 'أثر حرج',
    tone: 'border-rose-500/40 bg-rose-500/10 text-rose-700 dark:text-rose-300',
  },
  High: {
    en: 'High Impact',
    ar: 'أثر مرتفع',
    tone: 'border-orange-500/40 bg-orange-500/10 text-orange-700 dark:text-orange-300',
  },
  Medium: {
    en: 'Medium Impact',
    ar: 'أثر متوسط',
    tone: 'border-slate-500/40 bg-slate-500/10 text-slate-700 dark:text-slate-300',
  },
};

export function RecommendationCard({
  id,
  title,
  category,
  currentStatus,
  targetPhase,
  owner,
  impact,
  recommendedResolution,
  trace,
  children,
}: RecommendationCardProps) {
  const { pathname } = useLocation();
  const lang: 'en' | 'ar' = pathname.startsWith('/ar/') ? 'ar' : 'en';

  const cat = categoryLabels[category] ?? categoryLabels['open-decision'];
  const stat = statusLabels[currentStatus] ?? statusLabels.Open;
  const imp = impactStyles[impact] ?? impactStyles.High;

  return (
    <section
      id={id.toLowerCase()}
      aria-label={`${id} — ${title}`}
      className="my-6 scroll-mt-24 overflow-hidden rounded-2xl border border-fd-border bg-fd-card text-fd-card-foreground shadow-xs"
    >
      <div className="not-prose flex flex-wrap items-center justify-between gap-2 border-b border-fd-border bg-fd-secondary/50 px-4 py-2.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="inline-flex items-center gap-1.5 rounded-md bg-fd-primary/10 px-2 py-0.5 font-mono text-xs font-semibold text-fd-primary">
            <Lightbulb className="size-3.5 shrink-0" />
            {id}
          </span>
          <span className="rounded-md border border-fd-border bg-fd-background px-2 py-0.5 text-xs font-medium text-fd-foreground">
            {lang === 'ar' ? cat.ar : cat.en}
          </span>
          <span className={cn('rounded-full border px-2.5 py-0.5 text-xs font-medium', stat.tone)}>
            {lang === 'ar' ? stat.ar : stat.en}
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="inline-flex items-center gap-1 rounded-md border border-fd-border bg-fd-background px-2 py-0.5 text-fd-muted-foreground">
            <CalendarClock className="size-3.5" />
            {targetPhase}
          </span>
          <span className="inline-flex items-center gap-1 rounded-md border border-fd-border bg-fd-background px-2 py-0.5 text-fd-muted-foreground">
            <UserCheck className="size-3.5" />
            {owner}
          </span>
          <span className={cn('inline-flex items-center gap-1 rounded-full border px-2 py-0.5 font-semibold', imp.tone)}>
            <ShieldAlert className="size-3" />
            {lang === 'ar' ? imp.ar : imp.en}
          </span>
        </div>
      </div>

      <div className="px-4 py-3.5">
        <div className="not-prose mb-3 rounded-xl border border-emerald-500/35 bg-emerald-500/10 px-3.5 py-2.5 text-sm">
          <p className="mb-1 text-xs font-bold uppercase tracking-wider text-emerald-700 dark:text-emerald-300">
            {lang === 'ar' ? 'القرار / التوصية المقترحة للاعتماد' : 'Recommended Resolution'}
          </p>
          <p className="font-medium leading-relaxed text-fd-foreground">{recommendedResolution}</p>
        </div>

        <div className="prose prose-sm max-w-none dark:prose-invert prose-p:my-2 prose-ul:my-2 prose-li:my-0.5">
          {children}
        </div>

        <TraceBar {...trace} />
      </div>
    </section>
  );
}
