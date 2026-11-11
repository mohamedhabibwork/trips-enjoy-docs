import type { LucideIcon } from 'lucide-react';
import {
  Briefcase,
  Building2,
  Camera,
  Car,
  ClipboardList,
  Cog,
  Database,
  Eye,
  HeartHandshake,
  Layers,
  Lightbulb,
  ListTodo,
  Map,
  Network,
  Palette,
  Route,
  Shield,
  Users,
} from 'lucide-react';

/**
 * All copy and featured numbers for the landing page live here,
 * so the page layout never needs to change when the docs evolve.
 *
 * Copy is keyed by locale; `landing(locale)` is the single accessor the
 * page uses. English is the source of truth — Arabic mirrors it.
 */

export type LandingHero = {
  badge: string;
  tagline: string;
  subtitle: string;
};

export type LandingSection = {
  /** docs folder slug, becomes the `/$lang/docs/<slug>` link target */
  slug: string;
  title: string;
  description: string;
  /** small line under the description, e.g. the ID vocabulary of the doc */
  meta: string;
};

export type LandingRole = {
  /** page under the user-flows folder */
  slug: string;
  label: string;
};

export type LandingIdPrefix = {
  prefix: string;
  meaning: string;
};

/** Strings that live directly on the page (CTAs, section headings, AI blurb). */
export type LandingChrome = {
  openDocs: string;
  startBusiness: string;
  setEyebrow: string;
  setTitle: string;
  setDescription: string;
  rolesEyebrow: string;
  rolesTitle: string;
  rolesDescription: string;
  conventionsEyebrow: string;
  conventionsTitle: string;
  conventionsDescription: string;
  aiTitle: string;
  aiDescription: string;
};

export type LandingContent = {
  edition: string;
  hero: LandingHero;
  stats: { value: string; label: string }[];
  sections: (LandingSection & { icon: LucideIcon })[];
  roles: (LandingRole & { icon: LucideIcon })[];
  idPrefixes: LandingIdPrefix[];
  chrome: LandingChrome;
};

const content: Record<'en' | 'ar', LandingContent> = {
  en: {
    edition: 'Product documentation · v1.1 · 9 November 2026',
    hero: {
      badge: 'Product documentation · v1.1 · 9 November 2026',
      // TODO(habib): this tagline is the page's voice — tune it (see request below).
      tagline: 'From business case to booking flow, one traceable set.',
      subtitle:
        'Trips Enjoy is a multi-role trips marketplace — travelers book seats on organized trips, organizations publish and operate them, and drivers, photographers and moderators deliver the experience. Explore the complete end-to-end blueprint: business and software specs, data model, role flows, workflows, 6-phase delivery tasks, architecture, design system and decision resolutions — all cross-referenced by stable IDs.',
    },
    stats: [
      { value: '213', label: 'Functional requirements' },
      { value: '231', label: 'Delivery tasks (P1–P6)' },
      { value: '14', label: 'Data domains' },
      { value: '106', label: 'Role flows' },
      { value: '7 + API', label: 'Client apps & public API' },
    ],
    sections: [
      {
        slug: 'system',
        title: 'End-to-End System & Mindmaps',
        description:
          'Unified 8-stage operational lifecycle from onboarding and deals to live trip tracking, settlements and the platform mindmap atlas.',
        meta: 'STAGE-01..08 · Mindmap Atlas · Full lifecycle trace',
        icon: Layers,
      },
      {
        slug: 'brd',
        title: 'Business (BRD)',
        description:
          'Objectives, scope, stakeholders, commercial model, 30 business rules, risks and 32 tracked open decisions.',
        meta: 'BR · BRL · RSK · OD IDs',
        icon: Briefcase,
      },
      {
        slug: 'srs',
        title: 'Software (SRS)',
        description:
          '213 functional requirements across 28 modules, data rules, 10 state machines and non-functional quality goals.',
        meta: 'FR IDs · MoSCoW priorities · BRD traceability',
        icon: ClipboardList,
      },
      {
        slug: 'erd',
        title: 'Data Model (ERD)',
        description:
          '14 domains of PostgreSQL & TimescaleDB tables, UUIDv7 conventions, lookup catalog and row-level security paths.',
        meta: 'Immutable ledgers · TimescaleDB · RLS & ACL',
        icon: Database,
      },
      {
        slug: 'user-flows',
        title: 'User Flows',
        description:
          '106 role flows showing how every actor registers, gets approved and operates day to day — with rule traces and edge cases.',
        meta: 'UF IDs · BRL/FR traces · screen references',
        icon: Route,
      },
      {
        slug: 'workflows',
        title: 'System Workflows',
        description:
          'Engine-executed processes — 10 state machines, countdown timers, backoff retries and scheduled job cadences.',
        meta: 'Idempotency · Outbox · Scheduled job catalog',
        icon: Cog,
      },
      {
        slug: 'plan',
        title: 'Delivery Plan',
        description:
          '231 rich delivery tasks (213 functional + 18 platform) across P1–P6 with acceptance criteria, tables touched and gates.',
        meta: 'T-P1..P6 IDs · Milestones · Test pyramid',
        icon: ListTodo,
      },
      {
        slug: 'architecture',
        title: 'Architecture',
        description:
          'Modular monolith topology, PostgreSQL/RLS & Redis data stores, gateway integrations, deployment and NFR enforcement.',
        meta: 'System context · Outbox · NFR budgets',
        icon: Network,
      },
      {
        slug: 'design',
        title: 'Design System',
        description:
          'Semantic tokens, 15-component shared kit, Arabic-first RTL & Western numeral rules and WCAG 2.1 AA commitments.',
        meta: 'Tokens · RTL & numerals · 7 app surfaces',
        icon: Palette,
      },
      {
        slug: 'recommendations',
        title: 'Recommendations & Resolutions',
        description:
          'MENA-benchmarked recommended resolutions for all 32 OD-* decisions and 15 [OPEN] items, plus 12 engineering blueprints.',
        meta: 'REC-* blueprints · OD-01..32 resolutions',
        icon: Lightbulb,
      },
    ],
    roles: [
      { slug: 'traveler', label: 'Traveler', icon: Map },
      { slug: 'organization', label: 'Organization', icon: Building2 },
      { slug: 'admin', label: 'Admin', icon: Shield },
      { slug: 'driver', label: 'Driver', icon: Car },
      { slug: 'photographer', label: 'Photographer', icon: Camera },
      { slug: 'moderator', label: 'Moderator', icon: Eye },
      { slug: 'parent', label: 'Parent tracker', icon: HeartHandshake },
      { slug: 'shared', label: 'Shared flows', icon: Users },
    ],
    idPrefixes: [
      { prefix: 'BR-*', meaning: 'Business requirements' },
      { prefix: 'BRL-*', meaning: 'Business rules' },
      { prefix: 'FR-*', meaning: 'Functional requirements (MoSCoW-graded)' },
      { prefix: 'D-*', meaning: 'Decided values from the decision register' },
      { prefix: 'OD-*', meaning: 'Open decisions with recommended resolutions' },
      { prefix: 'UF-*', meaning: 'User flows across 8 role suites' },
      { prefix: 'T-*', meaning: 'Phase delivery tasks (functional & platform)' },
      { prefix: 'REC-*', meaning: 'Architecture, security & operations blueprints' },
    ],
    chrome: {
      openDocs: 'Open the docs',
      startBusiness: 'Start with the business case',
      setEyebrow: 'The set',
      setTitle: 'Ten pillars, one traceable vocabulary',
      setDescription:
        'Every document cites the others by stable ID, so a business rule traces cleanly down to a requirement, a database table, a flow, a delivery task and an engineering recommendation.',
      rolesEyebrow: 'User flows',
      rolesTitle: 'Follow a role',
      rolesDescription:
        'Each app has its own flow file: registration, approval, and everything the role can do once active.',
      conventionsEyebrow: 'Conventions',
      conventionsTitle: 'How to read it',
      conventionsDescription:
        'Requirements, tasks and recommendations cite each other by ID prefix. Every SRS page lists its MoSCoW priority and upstream BRD IDs; [OPEN] and OD-* items link directly to concrete recommended resolutions.',
      aiTitle: 'Built for AI agents too',
      aiDescription: 'The whole set is served as plain text and via MCP for LLM consumption.',
    },
  },
  ar: {
    edition: 'وثائق المنتج · الإصدار 1.1 · 9 نوفمبر 2026',
    hero: {
      badge: 'وثائق المنتج · الإصدار 1.1 · 9 نوفمبر 2026',
      tagline: 'من دراسة العمل إلى مسار الحجز، منظومة واحدة قابلة للتتبع.',
      subtitle:
        'تريبس إنجوي منصة رحلات متعددة الأدوار — المسافرون يحجزون مقاعد في رحلات منظمة، والجهات المنظِّمة تنشر هذه الرحلات وتديرها، بينما يقدّم السائقون والمصوّرون والمشرفون التجربة على أرض الواقع. استكشف المخطط الشامل من البداية إلى النهاية: متطلبات العمل والبرمجيات، ونموذج البيانات، وتدفقات الأدوار، وسير العمل، ومهام التسليم عبر 6 مراحل، والمعمارية، ونظام التصميم، والتوصيات والقرارات المقترحة — جميعها مترابطة بمعرّفات ثابتة.',
    },
    stats: [
      { value: '213', label: 'متطلبًا وظيفيًا' },
      { value: '231', label: 'مهمة تسليم (P1–P6)' },
      { value: '14', label: 'نطاق بيانات' },
      { value: '106', label: 'تدفق أدوار' },
      { value: '7 + API', label: 'تطبيقات عملاء وواجهة API' },
    ],
    sections: [
      {
        slug: 'system',
        title: 'النظام الشامل والخرائط الذهنية',
        description:
          'دورة الحياة التشغيلية الموحّدة من 8 مراحل بدءًا من الانضمام والصفقات حتى التتبع الحي والتسويات المالية وأطلس الخرائط الذهنية.',
        meta: 'STAGE-01..08 · أطلس الخرائط الذهنية · تتبع شامل',
        icon: Layers,
      },
      {
        slug: 'brd',
        title: 'المتطلبات التجارية (BRD)',
        description:
          'الأهداف والنطاق وأصحاب المصلحة والنموذج التجاري و30 قاعدة عمل والمخاطر و32 قرارًا مفتوحًا متتبَّعًا.',
        meta: 'معرّفات BR · BRL · RSK · OD',
        icon: Briefcase,
      },
      {
        slug: 'srs',
        title: 'المتطلبات البرمجية (SRS)',
        description:
          '213 متطلبًا وظيفيًا عبر 28 وحدة، وقواعد البيانات، و10 آلات حالات، وأهداف الجودة غير الوظيفية.',
        meta: 'معرّفات FR · أولويات MoSCoW · تتبع إلى BRD',
        icon: ClipboardList,
      },
      {
        slug: 'erd',
        title: 'نموذج البيانات (ERD)',
        description:
          '14 نطاقًا من جداول PostgreSQL وTimescaleDB، واصطلاحات UUIDv7، وكتالوج القيم المرجعية، ومسارات أمان الصفوف (RLS).',
        meta: 'سجلات غير قابلة للتعديل · TimescaleDB · RLS وACL',
        icon: Database,
      },
      {
        slug: 'user-flows',
        title: 'تدفقات المستخدمين',
        description:
          '106 تدفقات للأدوار توضّح كيف يسجّل كل فاعل ويُعتمد ويعمل يومًا بعد يوم — مع تتبع القواعد والحالات الحدية.',
        meta: 'معرّفات UF · تتبع BRL/FR · مراجع الشاشات',
        icon: Route,
      },
      {
        slug: 'workflows',
        title: 'سير العمل في النظام',
        description:
          'العمليات التي ينفّذها المحرّك — 10 آلات حالات، ومؤقتات العد التنازلي، وإعادات المحاولة، وإيقاعات المهام المجدولة.',
        meta: 'منع التكرار · Outbox · فهرس المهام المجدولة',
        icon: Cog,
      },
      {
        slug: 'plan',
        title: 'خطة التسليم',
        description:
          '231 مهمة تسليم غنية بالتفاصيل (213 وظيفية + 18 للمنصة) عبر P1–P6 مع معايير القبول والجداول المتأثرة وبوابات المراحل.',
        meta: 'معرّفات T-P1..P6 · المعالم الرئيسية · هرم الاختبارات',
        icon: ListTodo,
      },
      {
        slug: 'architecture',
        title: 'المعمارية',
        description:
          'طوبولوجيا البنية الأحادية المعيارية، ومخازن PostgreSQL/RLS وRedis، وتكاملات البوابات، والنشر وفرض سمات الجودة.',
        meta: 'سياق النظام · Outbox · ميزانيات NFR',
        icon: Network,
      },
      {
        slug: 'design',
        title: 'نظام التصميم',
        description:
          'الرموز الدلالية، ومجموعة المكونات المشتركة من 15 مكوّنًا، وقواعد RTL والأرقام الغربية والتزامات WCAG 2.1 AA.',
        meta: 'رموز التصميم · RTL والأرقام · 7 واجهات تطبيقات',
        icon: Palette,
      },
      {
        slug: 'recommendations',
        title: 'التوصيات والقرارات المقترحة',
        description:
          'توصيات عملية معايَرة لأسواق المنطقة لحسم جميع القرارات الـ32 (OD-*) و15 بندًا مفتوحًا [OPEN]، مع 12 مخططًا هندسيًا.',
        meta: 'مخططات REC-* · حسم القرارات OD-01..32',
        icon: Lightbulb,
      },
    ],
    roles: [
      { slug: 'traveler', label: 'المسافر', icon: Map },
      { slug: 'organization', label: 'الجهة المنظِّمة', icon: Building2 },
      { slug: 'admin', label: 'مشرف المنصة', icon: Shield },
      { slug: 'driver', label: 'السائق', icon: Car },
      { slug: 'photographer', label: 'المصوّر', icon: Camera },
      { slug: 'moderator', label: 'المشرف', icon: Eye },
      { slug: 'parent', label: 'متابع الوالدين', icon: HeartHandshake },
      { slug: 'shared', label: 'تدفقات مشتركة', icon: Users },
    ],
    idPrefixes: [
      { prefix: 'BR-*', meaning: 'متطلبات العمل' },
      { prefix: 'BRL-*', meaning: 'قواعد العمل' },
      { prefix: 'FR-*', meaning: 'المتطلبات الوظيفية (مصنّفة وفق MoSCoW)' },
      { prefix: 'D-*', meaning: 'قيم محسومة من سجل القرارات' },
      { prefix: 'OD-*', meaning: 'قرارات مفتوحة مقرونة بتوصيات مقترحة للحسم' },
      { prefix: 'UF-*', meaning: 'تدفقات المستخدمين عبر 8 مجموعات أدوار' },
      { prefix: 'T-*', meaning: 'مهام التسليم المرحلية (وظيفية وتأسيسية للمنصة)' },
      { prefix: 'REC-*', meaning: 'توصيات ومخططات المعمارية والأمان والتشغيل' },
    ],
    chrome: {
      openDocs: 'تصفّح الوثائق',
      startBusiness: 'ابدأ من دراسة العمل',
      setEyebrow: 'المجموعة',
      setTitle: 'عشر ركائز، ومعجم واحد قابل للتتبع',
      setDescription:
        'كل مستند يستشهد بالآخرين بمعرّف ثابت، فتنسحب قاعدة العمل بوضوح نزولًا إلى متطلب وجدول وتدفق ومهمة تسليم وتوصية هندسية.',
      rolesEyebrow: 'تدفقات المستخدمين',
      rolesTitle: 'تابع دورًا محددًا',
      rolesDescription:
        'لكل تطبيق ملف تدفق خاص: التسجيل، والاعتماد، وكل ما يستطيع الدور فعله بعد تفعيله.',
      conventionsEyebrow: 'الاصطلاحات',
      conventionsTitle: 'كيف تقرأ الوثائق',
      conventionsDescription:
        'تستشهد المتطلبات والمهام والتوصيات ببعضها عبر بادئات المعرّفات. كل صفحة SRS تذكر أولوية MoSCoW ومعرّفات BRD المصدرية؛ وترتبط العناصر [OPEN] وOD-* مباشرةً بالقرارات المقترحة للحسم.',
      aiTitle: 'مصمَّمة أيضًا لوكلاء الذكاء الاصطناعي',
      aiDescription: 'تُقدَّم المجموعة كاملة كنص عادي وعبر خادم MCP لاستهلاك نماذج اللغة.',
    },
  },
};

/** Locale-aware accessor for everything the landing page renders. */
export function landing(locale: string): LandingContent {
  return content[locale as keyof typeof content] ?? content.en;
}

/** Edition line shared by the landing hero and the docs sidebar banner. */
export const docsEdition = content.en.edition;
