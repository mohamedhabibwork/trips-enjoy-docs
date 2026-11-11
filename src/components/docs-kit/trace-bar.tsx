import { useLocation } from '@tanstack/react-router';
import Link from 'fumadocs-core/link';
import {
  Briefcase,
  ClipboardList,
  Cog,
  Database,
  Layers,
  Lightbulb,
  ListTodo,
  Network,
  Palette,
  Route,
  type LucideIcon,
} from 'lucide-react';
import { arAnchors } from '@/lib/ar-anchors';
import { cn } from '@/lib/cn';

export type TraceLinkItem = {
  label: string;
  href: string;
  detail?: string;
};

export interface TraceBarProps {
  brd?: TraceLinkItem[];
  srs?: TraceLinkItem[];
  erd?: TraceLinkItem[];
  plan?: TraceLinkItem[];
  workflows?: TraceLinkItem[];
  userFlows?: TraceLinkItem[];
  architecture?: TraceLinkItem[];
  design?: TraceLinkItem[];
  system?: TraceLinkItem[];
  recommendations?: TraceLinkItem[];
  compact?: boolean;
  className?: string;
}

type PillarKey =
  | 'brd'
  | 'srs'
  | 'erd'
  | 'plan'
  | 'workflows'
  | 'userFlows'
  | 'architecture'
  | 'design'
  | 'system'
  | 'recommendations';

type PillarConfig = {
  key: PillarKey;
  en: string;
  ar: string;
  icon: LucideIcon;
  tone: string;
};

const PILLARS: PillarConfig[] = [
  {
    key: 'brd',
    en: 'BRD',
    ar: 'الأعمال (BRD)',
    icon: Briefcase,
    tone: 'border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  },
  {
    key: 'srs',
    en: 'SRS',
    ar: 'البرمجيات (SRS)',
    icon: ClipboardList,
    tone: 'border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-300',
  },
  {
    key: 'erd',
    en: 'ERD',
    ar: 'البيانات (ERD)',
    icon: Database,
    tone: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  },
  {
    key: 'plan',
    en: 'Plan',
    ar: 'الخطة',
    icon: ListTodo,
    tone: 'border-violet-500/30 bg-violet-500/10 text-violet-700 dark:text-violet-300',
  },
  {
    key: 'workflows',
    en: 'Workflows',
    ar: 'سير العمل',
    icon: Cog,
    tone: 'border-indigo-500/30 bg-indigo-500/10 text-indigo-700 dark:text-indigo-300',
  },
  {
    key: 'userFlows',
    en: 'User Flows',
    ar: 'تدفقات المستخدم',
    icon: Route,
    tone: 'border-cyan-500/30 bg-cyan-500/10 text-cyan-700 dark:text-cyan-300',
  },
  {
    key: 'architecture',
    en: 'Architecture',
    ar: 'المعمارية',
    icon: Network,
    tone: 'border-slate-500/30 bg-slate-500/10 text-slate-700 dark:text-slate-300',
  },
  {
    key: 'design',
    en: 'Design',
    ar: 'التصميم',
    icon: Palette,
    tone: 'border-pink-500/30 bg-pink-500/10 text-pink-700 dark:text-pink-300',
  },
  {
    key: 'system',
    en: 'End-to-End',
    ar: 'النظام الشامل',
    icon: Layers,
    tone: 'border-teal-500/30 bg-teal-500/10 text-teal-700 dark:text-teal-300',
  },
  {
    key: 'recommendations',
    en: 'Recommendations',
    ar: 'التوصيات',
    icon: Lightbulb,
    tone: 'border-rose-500/30 bg-rose-500/10 text-rose-700 dark:text-rose-300',
  },
];

/**
 * Ensures internal doc links follow the active locale (`/en/docs/...` vs `/ar/docs/...`),
 * translating EN heading anchors via `arAnchors` when rendered inside `/ar/` routes.
 */
export function localizeDocHref(href: string, lang: 'en' | 'ar'): string {
  if (lang === 'en' || !href.startsWith('/en/docs')) return href;
  const rest = href.slice('/en/docs'.length); // e.g. "/srs/quality#82-security-nfr-sec" or ""
  if (rest === '' || rest === '/') return '/ar/docs';
  const [rawPath, anchor] = rest.slice(1).split('#');
  const pageKeyCandidates = [rawPath, `${rawPath}/index`];
  let mappedAnchor = anchor;
  if (anchor) {
    for (const key of pageKeyCandidates) {
      const found = arAnchors[key]?.[anchor];
      if (found) {
        mappedAnchor = found;
        break;
      }
    }
  }
  return mappedAnchor ? `/ar/docs/${rawPath}#${mappedAnchor}` : `/ar/docs/${rawPath}`;
}

export function TraceBar({
  compact = false,
  className,
  ...pillars
}: TraceBarProps) {
  const { pathname } = useLocation();
  const lang: 'en' | 'ar' =
    pathname === '/ar' || pathname.startsWith('/ar/') ? 'ar' : 'en';

  const active = PILLARS.map((cfg) => ({
    cfg,
    items: pillars[cfg.key] ?? [],
  })).filter((entry) => entry.items.length > 0);

  if (active.length === 0) return null;

  return (
    <div
      className={cn(
        'not-prose rounded-xl border border-fd-border bg-fd-secondary/40 px-3 py-2.5 text-xs',
        compact ? 'my-2' : 'mt-3',
        className,
      )}
    >
      <div className="mb-1.5 flex items-center justify-between text-[11px] font-semibold uppercase tracking-wider text-fd-muted-foreground">
        <span>{lang === 'ar' ? 'روابط التتبع الشاملة عبر الوثائق' : 'Cross-Specification Traceability'}</span>
      </div>
      <div className="flex flex-col gap-1.5">
        {active.map(({ cfg, items }) => {
          const Icon = cfg.icon;
          return (
            <div key={cfg.key} className="flex flex-wrap items-center gap-1.5">
              <span
                className={cn(
                  'inline-flex shrink-0 items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px] font-semibold',
                  cfg.tone,
                )}
              >
                <Icon className="size-3 shrink-0" />
                <span>{lang === 'ar' ? cfg.ar : cfg.en}</span>
              </span>
              {items.map((item, idx) => (
                <Link
                  key={`${cfg.key}-${item.href}-${item.label}-${idx}`}
                  href={localizeDocHref(item.href, lang)}
                  className="inline-flex items-center gap-1 rounded-md border border-fd-border bg-fd-card px-2 py-0.5 font-medium text-fd-foreground no-underline transition-colors hover:border-fd-primary/50 hover:bg-fd-accent hover:text-fd-primary"
                >
                  <span>{item.label}</span>
                  {item.detail ? (
                    <span className="text-[10px] text-fd-muted-foreground">· {item.detail}</span>
                  ) : null}
                </Link>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}
