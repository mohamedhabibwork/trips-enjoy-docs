#!/usr/bin/env bun
/**
 * Generates the enriched phased Delivery Plan (`plan/p1..p6.mdx` + `plan/milestones.mdx`
 * in English, and `ar/plan/p1..p6.mdx` + `ar/plan/milestones.mdx` via `generate-plan-ar.ts`)
 * from the living documentation set:
 *
 *   - 213 Functional <TaskCard /> blocks + 18 Platform <TaskCard /> blocks = 231 tasks/locale
 *   - Full inline SRS specification, implementation deliverables, Given/When/Then acceptance
 *     criteria, exact ERD tables, and upstream BRD business requirements & BRL rules
 *   - Per-phase <MindmapBlock /> + Quick-Jump Task Index + Decisions-to-Close table
 *   - Complete 10-pillar <TraceBar /> (BRD, SRS, ERD, Plan, Workflows, User Flows,
 *     Architecture, Design, End-to-End System, and Recommendations)
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';
import { arAnchors } from '../src/lib/ar-anchors';
import { MODULE_META, MODULE_ORDER, PHASES, type PhaseId } from './plan-shared';

const DOCS = join(fileURLToPath(new URL('..', import.meta.url)), 'content', 'docs');

const odAnchorCache = new Map<string, Map<string, string>>();
/** Full heading-slug anchor for an OD id on the locale's open-decisions page
 *  (e.g. `od-11--payment--payout-gateways-per-country`), falling back to the
 *  short id anchor when the heading is missing. */
function odAnchorFor(docRoot: string, od: string): string {
  let map = odAnchorCache.get(docRoot);
  if (!map) {
    map = new Map();
    const slugger = new GithubSlugger();
    for (const line of readFileSync(join(docRoot, 'recommendations/open-decisions.mdx'), 'utf8').split('\n')) {
      const h = line.match(/^### (OD-\d+) — (.+)$/);
      if (h) map.set(h[1].toLowerCase(), slugger.slug(`${h[1]} — ${h[2].trim()}`));
    }
    odAnchorCache.set(docRoot, map);
  }
  return map.get(od.toLowerCase()) ?? od.toLowerCase();
}

type LinkItem = { label: string; href: string; detail?: string };

type ParsedFr = {
  id: string;
  title: string;
  module: string;
  moduleTitle: string;
  priority: 'Must' | 'Should' | 'Could';
  brs: string[];
  brls: string[];
  ods: string[];
  specText: string;
  acceptance: string[];
};

type OdRow = {
  id: string;
  topic: string;
  status: string;
  owner: string;
};

function sanitizeMindmapNode(text: string): string {
  return text
    .replace(/[()[\]:;"{}]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function docHref(lang: 'en' | 'ar', pagePath: string, enAnchor?: string): string {
  const base = `/${lang}/docs/${pagePath}`.replace(/\/index$/, '');
  if (!enAnchor) return base;
  if (lang === 'en') return `${base}#${enAnchor}`;
  const candidates = [pagePath, `${pagePath}/index`];
  for (const key of candidates) {
    const mapped = arAnchors[key]?.[enAnchor];
    if (mapped) return `${base}#${mapped}`;
  }
  return `${base}#${enAnchor}`;
}

/** Parse phase assignments deterministically from canonical EN BRD + Traceability so EN & AR never diverge. */
function computeCanonicalPhaseMap(): { phaseOf: Map<string, PhaseId>; brCount: number } {
  const brPhase = new Map<string, PhaseId>();
  for (const line of readFileSync(join(DOCS, 'brd/requirements.mdx'), 'utf8').split('\n')) {
    const m = line.match(/^\*\*(?:\[)?(BR-[A-Z]+-\d+)(?:\]\([^)]*\))?\s*·\s*[MSC] · (.+?)\*\* — /);
    if (!m) continue;
    const phases = [...m[2].matchAll(/P[1-6]/g)].map((p) => p[0] as PhaseId);
    if (phases.length === 0) continue;
    phases.sort();
    brPhase.set(m[1], phases[0]);
  }

  const frPhaseFallback = new Map<string, PhaseId>();
  for (const line of readFileSync(join(DOCS, 'srs/traceability.mdx'), 'utf8').split('\n')) {
    if (!/^\| \[BR-[A-Z]+-\d+\]\(/.test(line)) continue;
    const br = line.match(/\[BR-[A-Z]+-\d+\]/)![0].slice(1, -1);
    const phase = brPhase.get(br);
    if (!phase) continue;
    for (const fr of line.matchAll(/\[(FR-[A-Z]+-\d+)\]\(/g)) {
      if (!frPhaseFallback.has(fr[1])) frPhaseFallback.set(fr[1], phase);
    }
  }

  const enFrMeta: { id: string; module: string; brs: string[] }[] = [];
  for (const f of readdirSync(join(DOCS, 'srs/requirements')).filter((x) => x.endsWith('.mdx')).sort()) {
    const module = f.replace('.mdx', '').toUpperCase();
    const lines = readFileSync(join(DOCS, 'srs/requirements', f), 'utf8').split('\n');
    for (let i = 0; i < lines.length; i++) {
      const h = lines[i].match(/^## (FR-[A-Z]+-\d+) — (.+)$/);
      if (!h) continue;
      const metaLine = lines.slice(i + 1, i + 5).find((l) => l.includes('<ReqMeta')) ?? '';
      const meta = metaLine.match(/<ReqMeta priority="\w+" source="([^"]+)" \/>/);
      const ids = (meta?.[1] ?? '').split(/[;,]/).map((s) => s.trim()).filter(Boolean);
      enFrMeta.push({
        id: h[1],
        module,
        brs: ids.filter((id) => /^BR-/.test(id)),
      });
    }
  }

  const phaseOf = new Map<string, PhaseId>();
  const unassigned: string[] = [];
  for (const fr of enFrMeta) {
    const phases = fr.brs.map((br) => brPhase.get(br)).filter(Boolean) as PhaseId[];
    if (phases.length > 0) {
      phases.sort();
      phaseOf.set(fr.id, phases[0]);
    } else if (frPhaseFallback.has(fr.id)) {
      phaseOf.set(fr.id, frPhaseFallback.get(fr.id)!);
    } else {
      unassigned.push(fr.id);
    }
  }

  for (const id of unassigned) {
    const fr = enFrMeta.find((f) => f.id === id)!;
    const modPhases = enFrMeta
      .filter((f) => f.module === fr.module && phaseOf.has(f.id))
      .map((f) => phaseOf.get(f.id)!)
      .sort();
    if (modPhases.length > 0) phaseOf.set(id, modPhases[0]);
  }

  return { phaseOf, brCount: brPhase.size };
}

function parseLocaleFrs(docRoot: string): { frs: ParsedFr[]; frHref: Map<string, string>; urlPrefix: string } {
  const isAr = docRoot.endsWith('/ar');
  const urlPrefix = isAr ? '/ar/docs' : '/en/docs';
  const frs: ParsedFr[] = [];
  const frHref = new Map<string, string>();
  const slugger = new GithubSlugger();

  for (const f of readdirSync(join(docRoot, 'srs/requirements')).filter((x) => x.endsWith('.mdx')).sort()) {
    const module = f.replace('.mdx', '').toUpperCase();
    const lines = readFileSync(join(docRoot, 'srs/requirements', f), 'utf8').split('\n');
    const moduleTitle = (lines.find((l) => l.startsWith('title:')) ?? '')
      .replace(/^title:\s*/, '')
      .replace(/^["']|["']$/g, '')
      .trim();

    const headingIdxs: number[] = [];
    lines.forEach((line, idx) => {
      if (/^## FR-[A-Z]+-\d+ — /.test(line)) headingIdxs.push(idx);
    });

    for (let k = 0; k < headingIdxs.length; k++) {
      const start = headingIdxs[k];
      const end = k + 1 < headingIdxs.length ? headingIdxs[k + 1] : lines.length;
      const block = lines.slice(start, end);
      const h = block[0].match(/^## (FR-[A-Z]+-\d+) — (.+)$/)!;
      const id = h[1];
      const title = h[2].trim();

      slugger.reset();
      frHref.set(id, `${urlPrefix}/srs/requirements/${module.toLowerCase()}#${slugger.slug(`${id} — ${title}`)}`);

      let priority: 'Must' | 'Should' | 'Could' = 'Must';
      let brs: string[] = [];
      let brls: string[] = [];
      let ods: string[] = [];
      const specLines: string[] = [];
      const acceptance: string[] = [];

      for (let j = 1; j < block.length; j++) {
        const ln = block[j].trim();
        if (!ln) continue;
        if (ln.includes('<ReqMeta')) {
          const meta = ln.match(/<ReqMeta priority="(\w+)" source="([^"]+)" \/>/);
          if (meta) {
            priority = (meta[1] as 'Must' | 'Should' | 'Could') || 'Must';
            const ids = meta[2].split(/[;,]/).map((s) => s.trim()).filter(Boolean);
            brs = ids.filter((x) => /^BR-/.test(x));
            brls = ids.filter((x) => /^BRL-/.test(x));
            ods = ids.filter((x) => /^OD-/.test(x));
          }
          continue;
        }
        if (ln.startsWith('- ')) {
          acceptance.push(ln.slice(2).trim());
        } else if (!ln.startsWith('## ')) {
          specLines.push(ln);
        }
      }

      frs.push({
        id,
        title,
        module,
        moduleTitle,
        priority,
        brs,
        brls,
        ods,
        specText: specLines.join(' '),
        acceptance,
      });
    }
  }

  return { frs, frHref, urlPrefix };
}

function parseBrdTexts(docRoot: string): {
  brText: Map<string, string>;
  brlText: Map<string, string>;
  brlAnchor: string;
  odInfo: Map<string, OdRow>;
  odSectionAnchor: string;
} {
  const brText = new Map<string, string>();
  const brlText = new Map<string, string>();
  let brlAnchor = '7-business-rules';
  const reqSlugger = new GithubSlugger();

  for (const line of readFileSync(join(docRoot, 'brd/requirements.mdx'), 'utf8').split('\n')) {
    const h2 = line.match(/^## (7\..+)$/);
    if (h2) {
      reqSlugger.reset();
      brlAnchor = reqSlugger.slug(h2[1].trim());
    }
    const brMatch = line.match(/^\*\*(?:\[)?(BR-[A-Z]+-\d+)(?:\]\([^)]*\))?\s*·\s*[MSC] · .+?\*\* — (.+)$/);
    if (brMatch) brText.set(brMatch[1], brMatch[2].trim());

    const brlMatch = line.match(/^\*\*(BRL-\d+)\*\* — (.+)$/);
    if (brlMatch) brlText.set(brlMatch[1], brlMatch[2].trim());
  }

  const odInfo = new Map<string, OdRow>();
  let odSectionAnchor = '11-open-decisions';
  const compSlugger = new GithubSlugger();

  for (const line of readFileSync(join(docRoot, 'brd/compliance.mdx'), 'utf8').split('\n')) {
    const h2 = line.match(/^## (11\..+)$/);
    if (h2) {
      compSlugger.reset();
      odSectionAnchor = compSlugger.slug(h2[1].trim());
    }
    if (!line.startsWith('|') || !line.includes('OD-')) continue;
    const cols = line.split('|').map((c) => c.trim()).filter(Boolean);
    if (cols.length < 5) continue;
    const idMatch = cols[0].match(/OD-\d+/);
    if (!idMatch) continue;
    odInfo.set(idMatch[0], {
      id: idMatch[0],
      topic: cols[1],
      status: cols[2],
      owner: cols[4],
    });
  }

  return { brText, brlText, brlAnchor, odInfo, odSectionAnchor };
}

function parseErdMappings(docRoot: string, urlPrefix: string, frModule: Map<string, string>) {
  const domainTitles = new Map<string, string>();
  const moduleDomains = new Map<string, Set<string>>();
  const frTables = new Map<string, { table: string; domainSlug: string }[]>();
  const moduleTables = new Map<string, { table: string; domainSlug: string }[]>();
  const allValidTables = new Map<string, string>(); // table -> domainSlug

  const RANGE_RE = /FR-([A-Z]+)-(\d+)(?:\]\([^)]*\))?\s*(?:to|إلى|وحتى|حتى)\s*\[?FR-\1-(\d+)/g;

  for (const f of readdirSync(join(docRoot, 'erd/domains')).filter((x) => x.endsWith('.mdx')).sort()) {
    const slug = f.replace('.mdx', '');
    const lines = readFileSync(join(docRoot, 'erd/domains', f), 'utf8').split('\n');
    const title = (lines.find((l) => l.startsWith('title:')) ?? `title: ${slug}`)
      .replace(/^title:\s*/, '')
      .replace(/^["']|["']$/g, '')
      .trim();
    domainTitles.set(slug, title);

    let currentTable = '';
    for (const line of lines) {
      const th = line.match(/^## ([a-z0-9_]+)\s*$/);
      if (th) {
        currentTable = th[1];
        allValidTables.set(currentTable, slug);
        continue;
      }

      const citedFrs = new Set<string>();
      for (const m of line.matchAll(/(?<![A-Z])(FR-[A-Z]+-\d+)\b/g)) citedFrs.add(m[1]);
      for (const r of line.matchAll(RANGE_RE)) {
        for (let n = Number(r[2]); n <= Number(r[3]); n++) {
          citedFrs.add(`FR-${r[1]}-${String(n).padStart(2, '0')}`);
        }
      }

      for (const frId of citedFrs) {
        const mod = frModule.get(frId);
        if (!mod) continue;
        const dSet = moduleDomains.get(mod) ?? new Set<string>();
        dSet.add(slug);
        moduleDomains.set(mod, dSet);

        if (currentTable) {
          const fList = frTables.get(frId) ?? [];
          if (!fList.some((x) => x.table === currentTable)) fList.push({ table: currentTable, domainSlug: slug });
          frTables.set(frId, fList);

          const mList = moduleTables.get(mod) ?? [];
          if (!mList.some((x) => x.table === currentTable)) mList.push({ table: currentTable, domainSlug: slug });
          moduleTables.set(mod, mList);
        }
      }
    }
  }

  return {
    domainTitles,
    moduleDomains,
    frTables,
    moduleTables,
    allValidTables,
    domainHref: (slug: string) => `${urlPrefix}/erd/domains/${slug}`,
    tableHref: (slug: string, table: string) => `${urlPrefix}/erd/domains/${slug}#${table}`,
  };
}

function collectCoveringLinks(docRoot: string, urlPrefix: string, lang: 'en' | 'ar') {
  const userFlowsByFr = new Map<string, LinkItem[]>();
  const workflowsByFr = new Map<string, LinkItem[]>();
  const RANGE_RE = /FR-([A-Z]+)-(\d+)(?:\]\([^)]*\))?\s*(?:to|إلى|وحتى|حتى)\s*\[?FR-\1-(\d+)/g;

  function scan(file: string, relDir: string, kind: 'uf' | 'wf') {
    const slugger = new GithubSlugger();
    const lines = readFileSync(file, 'utf8').split('\n');
    const pageTitle = (lines.find((l) => l.startsWith('title:')) ?? '')
      .replace(/^title:\s*/, '')
      .replace(/^["']|["']$/g, '')
      .trim();
    let anchor = '';
    let ufCode = '';
    let inFence = false;

    for (const line of lines) {
      if (/^```/.test(line.trim())) {
        inFence = !inFence;
        continue;
      }
      if (inFence) continue;

      const h = line.match(/^(#{2,3})\s+(.+)$/);
      if (h) {
        const clean = h[2]
          .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
          .replace(/`([^`]+)`/g, '$1')
          .replace(/\*\*([^*]+)\*\*/g, '$1')
          .trim();
        slugger.reset();
        anchor = `#${slugger.slug(clean)}`;
        ufCode = /^UF-[A-Z]+-\d+\./.test(clean) ? clean.match(/^(UF-[A-Z]+-\d+)/)![1] : '';
        continue;
      }
      if (line.trimStart().startsWith('<')) continue;

      const add = (frId: string) => {
        const targetMap = kind === 'uf' ? userFlowsByFr : workflowsByFr;
        const label = kind === 'uf'
          ? (ufCode || (lang === 'ar' ? pageTitle : relDir.split('/').pop()!))
          : (lang === 'ar' ? pageTitle : relDir.split('/').pop()!);
        const href = `${urlPrefix}/${relDir}${anchor}`;
        const list = targetMap.get(frId) ?? [];
        if (!list.some((x) => x.href === href)) list.push({ label, href });
        targetMap.set(frId, list);
      };

      for (const m of line.matchAll(/(?<![A-Z])(FR-[A-Z]+-\d+)\b/g)) add(m[1]);
      for (const r of line.matchAll(RANGE_RE)) {
        for (let n = Number(r[2]); n <= Number(r[3]); n++) {
          add(`FR-${r[1]}-${String(n).padStart(2, '0')}`);
        }
      }
    }
  }

  for (const f of readdirSync(join(docRoot, 'user-flows')).filter((x) => x.endsWith('.mdx') && x !== 'index.mdx').sort()) {
    scan(join(docRoot, 'user-flows', f), `user-flows/${f.replace('.mdx', '')}`, 'uf');
  }
  for (const f of readdirSync(join(docRoot, 'workflows')).filter((x) => x.endsWith('.mdx') && !['index.mdx', 'coverage.mdx'].includes(x)).sort()) {
    scan(join(docRoot, 'workflows', f), `workflows/${f.replace('.mdx', '')}`, 'wf');
  }

  return { userFlowsByFr, workflowsByFr };
}

export function generatePlanForLocale(lang: 'en' | 'ar') {
  const docRoot = lang === 'ar' ? join(DOCS, 'ar') : DOCS;
  const urlPrefix = lang === 'ar' ? '/ar/docs' : '/en/docs';
  const { phaseOf, brCount } = computeCanonicalPhaseMap();
  const { frs, frHref } = parseLocaleFrs(docRoot);
  const { brText, brlText, brlAnchor, odInfo, odSectionAnchor } = parseBrdTexts(docRoot);
  const frModule = new Map(frs.map((f) => [f.id, f.module]));
  const erd = parseErdMappings(docRoot, urlPrefix, frModule);
  const { userFlowsByFr, workflowsByFr } = collectCoveringLinks(docRoot, urlPrefix, lang);

  let totalFrTasks = 0;
  let totalPlatTasks = 0;
  const milestoneRows: string[] = [];

  for (const phase of PHASES) {
    const inPhase = frs.filter((fr) => phaseOf.get(fr.id) === phase.id);
    const byModule = new Map<string, ParsedFr[]>();
    for (const fr of inPhase) {
      const list = byModule.get(fr.module) ?? [];
      list.push(fr);
      byModule.set(fr.module, list);
    }
    const orderedModules = MODULE_ORDER.filter((m) => byModule.has(m));
    const counts = { Must: 0, Should: 0, Could: 0 };
    for (const fr of inPhase) counts[fr.priority] += 1;

    const totalPhaseTasks = inPhase.length + phase.platformTasks.length;
    const odsInPhase = new Set<string>();
    for (const fr of inPhase) for (const od of fr.ods) odsInPhase.add(od);
    const sortedOds = [...odsInPhase].sort();

    // Pre-assign task IDs for the quick-jump index
    const taskIndexRows: { taskId: string; modLabel: string; reqCell: string; prio: string; title: string }[] = [];
    for (const pt of phase.platformTasks) {
      taskIndexRows.push({
        taskId: pt.id,
        modLabel: 'PLAT',
        reqCell: pt.srsAnchors.map((a) => `[${lang === 'ar' ? a.labelAr : a.labelEn}](${docHref(lang, a.path, a.enAnchor)})`).join(', '),
        prio: 'Must',
        title: lang === 'ar' ? pt.titleAr : pt.titleEn,
      });
    }
    for (const mod of orderedModules) {
      const list = byModule.get(mod)!;
      list.forEach((fr, idx) => {
        const taskId = `T-${phase.id}-${mod}-${idx + 1}`;
        taskIndexRows.push({
          taskId,
          modLabel: mod,
          reqCell: `[${fr.id}](${frHref.get(fr.id)})`,
          prio: fr.priority,
          title: fr.title,
        });
      });
    }

    const out: string[] = [];
    const theme = lang === 'ar' ? phase.themeAr : phase.themeEn;
    const shortTitle = lang === 'ar' ? phase.shortAr : phase.shortEn;
    const done = lang === 'ar' ? phase.doneAr : phase.doneEn;

    out.push('---');
    out.push(`title: "${phase.id} — ${shortTitle}"`);
    out.push(
      lang === 'ar'
        ? `description: "مهام التسليم التفصيلية للمرحلة ${phase.id}: ${theme} — مولَّدة بالكامل مع المواصفات ومعايير القبول وروابط التتبع الشاملة."`
        : `description: "Detailed delivery tasks for ${phase.id}: ${theme} — generated with full specifications, acceptance criteria, tables, and cross-pillar traceability."`,
    );
    out.push('icon: Milestone');
    out.push('---');
    out.push('');

    if (lang === 'en') {
      out.push(`**Theme.** ${theme}`);
      out.push('');
      out.push(`**Definition of done.** ${done} *([BRD section 3.3](/en/docs/brd#33-delivery-phases))*`);
      out.push('');
      out.push(
        `**${totalPhaseTasks} delivery tasks** (**${inPhase.length}** functional tasks: **${counts.Must}** Must, **${counts.Should}** Should, **${counts.Could}** Could + **${phase.platformTasks.length}** cross-cutting platform foundation tasks) across **${orderedModules.length}** SRS modules. Every task below carries its complete specification, implementation deliverables, acceptance criteria, affected PostgreSQL tables, and cross-specification links across BRD, SRS, ERD, Plan, Workflows, User Flows, Architecture, Design, End-to-End Lifecycle, and Recommendations.`,
      );
      out.push('');
      out.push(
        `**Data model.** [ERD conventions](/en/docs/erd), [Access Control & RLS](/en/docs/erd/acl), and the [lookup catalog](/en/docs/erd/reference) apply to every table touched in this phase.`,
      );
    } else {
      out.push(`**المحور.** ${theme}`);
      out.push('');
      out.push(`**تعريف الإتمام.** ${done} *([القسم 3.3 من وثيقة الأعمال](/ar/docs/brd#${arAnchors['brd/index']?.['33-delivery-phases'] ?? '33-delivery-phases'}))*`);
      out.push('');
      out.push(
        `**${totalPhaseTasks} مهمة تسليم** (**${inPhase.length}** مهمة وظيفية: **${counts.Must}** إلزامية Must، و**${counts.Should}** مهمة Should، و**${counts.Could}** تحسينية Could + **${phase.platformTasks.length}** مهام بنية منصة مشتركة) موزعة على **${orderedModules.length}** وحدات برمجية. تتضمن كل مهمة أدناه المواصفة الكاملة ومخرجات التنفيذ الهندسي ومعايير القبول والجداول المتأثرة وروابط التتبع الشاملة عبر جميع وثائق النظام.`,
      );
      out.push('');
      out.push(
        `**نموذج البيانات.** تنطبق [اصطلاحات نموذج البيانات](/ar/docs/erd) و[ضوابط الوصول وأمان الصفوف RLS](/ar/docs/erd/acl) و[فهرس القيم المرجعية](/ar/docs/erd/reference) على جميع جداول هذه المرحلة.`,
      );
    }
    out.push('');

    // Phase MindmapBlock
    const phaseTrace = {
      brd: [{ label: lang === 'ar' ? 'مراحل التسليم §3.3' : 'BRD §3.3 Phases', href: docHref(lang, 'brd/index', '33-delivery-phases') }],
      srs: [{ label: lang === 'ar' ? 'مصفوفة التتبع' : 'SRS Traceability', href: `${urlPrefix}/srs/traceability` }],
      erd: [{ label: lang === 'ar' ? 'نموذج البيانات' : 'ERD Overview', href: `${urlPrefix}/erd` }],
      plan: [
        { label: lang === 'ar' ? 'المعالم وبوابات المراحل' : 'Milestones', href: `${urlPrefix}/plan/milestones` },
        { label: lang === 'ar' ? 'دليل البناء' : 'Build Guide', href: `${urlPrefix}/plan/build-guide` },
      ],
      workflows: [{ label: lang === 'ar' ? 'تغطية المتطلبات' : 'Workflow Coverage', href: `${urlPrefix}/workflows/coverage` }],
      architecture: [{ label: lang === 'ar' ? 'معمارية المنصة' : 'Architecture', href: `${urlPrefix}/architecture` }],
      design: [{ label: lang === 'ar' ? 'نظام التصميم' : 'Design System', href: `${urlPrefix}/design` }],
      system: [{ label: lang === 'ar' ? 'النظام الشامل' : 'End-to-End Lifecycle', href: `${urlPrefix}/system/lifecycle` }],
      recommendations: [{ label: lang === 'ar' ? 'حسم القرارات المفتوحة' : 'Open Decisions', href: `${urlPrefix}/recommendations/open-decisions` }],
    };

    out.push(
      `<MindmapBlock title=${JSON.stringify(
        lang === 'ar' ? `خريطة نطاق المرحلة ${phase.id} — ${shortTitle}` : `${phase.id} Scope Mindmap — ${shortTitle}`,
      )} caption=${JSON.stringify(
        lang === 'ar'
          ? `توزيع مهام البنية المشتركة والوحدات الوظيفية (${totalPhaseTasks} مهمة) وبوابة إغلاق المرحلة ${phase.id}.`
          : `Breakdown of platform foundations, module epics (${totalPhaseTasks} tasks), and milestone gates for ${phase.id}.`,
      )} trace={${JSON.stringify(phaseTrace)}}>`,
    );
    out.push('');
    out.push('```mermaid');
    out.push('mindmap');
    out.push(`  root(("${sanitizeMindmapNode(`${phase.id} — ${shortTitle}`)}"))`);
    out.push(`    ${lang === 'ar' ? 'البنية المشتركة للمنصة' : 'Platform Foundations'}`);
    for (const pt of phase.platformTasks) {
      out.push(`      ${sanitizeMindmapNode(`${pt.id} ${lang === 'ar' ? pt.titleAr : pt.titleEn}`)}`);
    }
    out.push(`    ${lang === 'ar' ? 'الوحدات الوظيفية' : 'Functional Module Epics'}`);
    for (const mod of orderedModules) {
      const mList = byModule.get(mod)!;
      const mShort = sanitizeMindmapNode(mList[0].moduleTitle.replace(/^[A-Z]+ — /, ''));
      out.push(`      ${sanitizeMindmapNode(`${mod} — ${mShort} — ${mList.length}`)}`);
    }
    if (sortedOds.length > 0) {
      out.push(`    ${lang === 'ar' ? 'قرارات تغلق قبل البناء' : 'Decisions Gate'}`);
      for (const od of sortedOds) {
        out.push(`      ${sanitizeMindmapNode(od)}`);
      }
    }
    out.push('```');
    out.push('');
    out.push('</MindmapBlock>');
    out.push('');

    // Quick-jump index table
    out.push(lang === 'ar' ? '## فهرس المهام السريع' : '## Quick-jump task index');
    out.push('');
    out.push(
      lang === 'ar'
        ? '| المهمة | الوحدة | المرجع | الأولوية |'
        : '| Task | Module | Reference | Priority |',
    );
    out.push('|---|---|---|---|');
    for (const row of taskIndexRows) {
      out.push(
        `| [${row.taskId} — ${row.title}](#${row.taskId.toLowerCase()}) | \`${row.modLabel}\` | ${row.reqCell} | ${row.prio} |`,
      );
    }
    out.push('');

    // Cross-cutting Platform Foundation Tasks
    out.push(lang === 'ar' ? '## عابرة للمجالات' : '## Cross-cutting');
    out.push('');
    out.push(
      lang === 'ar'
        ? 'تُبنى مهام البنية المشتركة هذه بالتوازي مع مهام الوحدات وتُشترط لاجتياز بوابة إغلاق المرحلة.'
        : 'These platform-wide engineering tasks establish the runtime, database, integration, and observability foundations required to close this phase gate.',
    );
    out.push('');

    for (const pt of phase.platformTasks) {
      totalPlatTasks += 1;
      const ptTitle = lang === 'ar' ? pt.titleAr : pt.titleEn;
      const verifiedTables = pt.tables.filter((t) => erd.allValidTables.has(t));
      const erdLinks: LinkItem[] = pt.erdDomainSlugs.map((slug) => ({
        label: erd.domainTitles.get(slug) ?? slug,
        href: erd.domainHref(slug),
      }));
      for (const tbl of verifiedTables) {
        const dSlug = erd.allValidTables.get(tbl)!;
        erdLinks.push({ label: tbl, href: erd.tableHref(dSlug, tbl), detail: 'table' });
      }
      erdLinks.push({ label: lang === 'ar' ? 'أمان الصفوف ACL' : 'ACL & RLS', href: `${urlPrefix}/erd/acl` });

      const ptTrace = {
        brd: [{ label: lang === 'ar' ? `المرحلة ${phase.id}` : `BRD ${phase.id} Gate`, href: docHref(lang, 'brd/index', '33-delivery-phases') }],
        srs: pt.srsAnchors.map((a) => ({
          label: lang === 'ar' ? a.labelAr : a.labelEn,
          href: docHref(lang, a.path, a.enAnchor),
        })),
        erd: erdLinks,
        plan: [
          { label: lang === 'ar' ? 'المعالم' : 'Milestones', href: `${urlPrefix}/plan/milestones` },
          { label: lang === 'ar' ? 'استراتيجية الاختبار' : 'Test Strategy', href: `${urlPrefix}/plan/testing` },
        ],
        workflows: [{ label: pt.workflowSlug, href: `${urlPrefix}/workflows/${pt.workflowSlug}` }],
        userFlows: [{ label: lang === 'ar' ? 'تدفقات الأدوار' : 'Role Flows', href: `${urlPrefix}/user-flows` }],
        architecture: [{ label: lang === 'ar' ? 'المعمارية' : 'Architecture', href: docHref(lang, 'architecture/index', pt.archAnchor) }],
        design: [{ label: lang === 'ar' ? 'نظام التصميم' : 'Design System', href: docHref(lang, 'design/index', pt.designAnchor) }],
        system: [{ label: pt.stageId.toUpperCase(), href: `${urlPrefix}/system/lifecycle#${pt.stageId}` }],
        recommendations: [{ label: pt.recId.toUpperCase(), href: `${urlPrefix}/recommendations/architecture-and-ops#${pt.recId}` }],
      };

      out.push(`### ${pt.id} — ${ptTitle}`);
      out.push('');
      out.push(
        `<TaskCard id=${JSON.stringify(pt.id)} title=${JSON.stringify(ptTitle)} phase=${JSON.stringify(phase.id)} module="PLAT" priority="Must" kind="platform" surfaces={${JSON.stringify(lang === 'ar' ? pt.surfacesAr : pt.surfacesEn)}} tables={${JSON.stringify(verifiedTables)}} trace={${JSON.stringify(ptTrace)}}>`,
      );
      out.push('');
      out.push(lang === 'ar' ? `**نطاق البنية المشتركة.** ${pt.specAr}` : `**Platform scope.** ${pt.specEn}`);
      out.push('');
      out.push(lang === 'ar' ? '**مخرجات التنفيذ الهندسي.**' : '**Implementation deliverables.**');
      out.push('');
      for (const d of lang === 'ar' ? pt.deliverablesAr : pt.deliverablesEn) out.push(`- ${d}`);
      out.push('');
      out.push(lang === 'ar' ? '**معايير القبول والتحقق.**' : '**Acceptance & verification criteria.**');
      out.push('');
      for (const a of lang === 'ar' ? pt.acceptanceAr : pt.acceptanceEn) out.push(`- ${a}`);
      out.push('');
      out.push('</TaskCard>');
      out.push('');
    }

    // Module Epics & Functional TaskCards
    for (const mod of orderedModules) {
      const list = byModule.get(mod)!;
      const meta = MODULE_META[mod];
      const modCleanTitle = list[0].moduleTitle.replace(/^[A-Z]+ — /, '');
      out.push(`## ${mod} — ${modCleanTitle}`);
      out.push('');

      const domainSlugs = [...(erd.moduleDomains.get(mod) ?? [])].sort();
      if (domainSlugs.length > 0) {
        const domainLinks = domainSlugs
          .map((slug) => `[${erd.domainTitles.get(slug) ?? slug}](${erd.domainHref(slug)})`)
          .join(', ');
        out.push(
          lang === 'ar'
            ? `**نموذج البيانات.** ${domainLinks} — النطاقات والجداول التي تقرؤها وتكتبها متطلبات هذه الوحدة.`
            : `**Data model.** ${domainLinks} — the data-model domains and tables this module's requirements read and write.`,
        );
        out.push('');
      }

      let seq = 0;
      for (const fr of list) {
        seq += 1;
        totalFrTasks += 1;
        const taskId = `T-${phase.id}-${mod}-${seq}`;

        // Exact tables for this FR (falling back to module's top tables if FR wasn't named on a specific table line)
        const specificTables = erd.frTables.get(fr.id) ?? (erd.moduleTables.get(mod) ?? []).slice(0, 4);
        const tableNames = specificTables.slice(0, 6).map((x) => x.table);

        // Build complete 10-pillar trace object
        const brdLinks: LinkItem[] = [];
        for (const br of fr.brs) {
          brdLinks.push({
            label: br,
            href: docHref(lang, 'brd/requirements', meta.brdAnchor),
            detail: phase.id,
          });
        }
        for (const brl of fr.brls) {
          brdLinks.push({
            label: brl,
            href: `${urlPrefix}/brd/requirements#${brlAnchor}`,
            detail: lang === 'ar' ? 'قاعدة' : 'rule',
          });
        }
        for (const od of fr.ods) {
          brdLinks.push({
            label: od,
            href: `${urlPrefix}/brd/compliance#${odSectionAnchor}`,
            detail: odInfo.get(od)?.status ?? 'OD',
          });
        }
        if (brdLinks.length === 0) {
          brdLinks.push({
            label: lang === 'ar' ? `قسم الأعمال ${mod}` : `BRD ${mod}`,
            href: docHref(lang, 'brd/requirements', meta.brdAnchor),
          });
        }

        const srsLinks: LinkItem[] = [
          { label: fr.id, href: frHref.get(fr.id)!, detail: fr.priority },
        ];
        if (meta.stateMachineAnchor) {
          srsLinks.push({
            label: lang === 'ar' ? meta.stateMachineAnchor.labelAr : meta.stateMachineAnchor.labelEn,
            href: docHref(lang, 'srs/state-machines', meta.stateMachineAnchor.enAnchor),
          });
        }
        srsLinks.push({
          label: lang === 'ar' ? 'معايير الجودة NFR' : 'Quality NFRs',
          href: `${urlPrefix}/srs/quality`,
        });

        const erdLinks: LinkItem[] = domainSlugs.map((slug) => ({
          label: erd.domainTitles.get(slug) ?? slug,
          href: erd.domainHref(slug),
        }));
        for (const t of specificTables.slice(0, 5)) {
          erdLinks.push({
            label: t.table,
            href: erd.tableHref(t.domainSlug, t.table),
            detail: 'table',
          });
        }
        if (erdLinks.length === 0) {
          erdLinks.push({ label: lang === 'ar' ? 'نموذج البيانات' : 'ERD Overview', href: `${urlPrefix}/erd` });
        }

        const ufLinks = (userFlowsByFr.get(fr.id) ?? []).slice(0, 5);
        if (ufLinks.length === 0) {
          ufLinks.push({
            label: meta.defaultUfSlug,
            href: `${urlPrefix}/user-flows/${meta.defaultUfSlug}`,
          });
        }

        const wfLinks = (workflowsByFr.get(fr.id) ?? []).slice(0, 4);
        if (wfLinks.length === 0) {
          wfLinks.push({
            label: meta.defaultWfSlug,
            href: `${urlPrefix}/workflows/${meta.defaultWfSlug}`,
          });
        }

        const recLinks: LinkItem[] = fr.ods.map((od) => ({
          label: lang === 'ar' ? `توصية ${od}` : `${od} Resolution`,
          href: `${urlPrefix}/recommendations/open-decisions#${odAnchorFor(docRoot, od)}`,
        }));
        recLinks.push({
          label: meta.recId.toUpperCase(),
          href: `${urlPrefix}/recommendations/architecture-and-ops#${meta.recId}`,
        });

        // Stage override for cancellation/refund FRs
        const isCancelOrRefund = /cancel|refund|remedy|إلغاء|استرداد|معالجة/i.test(`${fr.title} ${fr.specText}`);
        const stageId = isCancelOrRefund && ['BKG', 'PAY', 'CPN', 'TRIP'].includes(mod) ? 'stage-06' : meta.stageId;

        const traceObj = {
          brd: brdLinks,
          srs: srsLinks,
          erd: erdLinks,
          plan: [
            {
              label: lang === 'ar' ? `معلم ${phase.id}` : `${phase.id} Milestone`,
              href: `${urlPrefix}/plan/milestones`,
            },
            { label: lang === 'ar' ? 'استراتيجية الاختبار' : 'Testing Pyramid', href: `${urlPrefix}/plan/testing` },
          ],
          workflows: wfLinks,
          userFlows: ufLinks,
          architecture: [
            {
              label: lang === 'ar' ? 'المعمارية' : 'Architecture',
              href: docHref(lang, 'architecture/index', meta.archAnchor),
            },
          ],
          design: [
            {
              label: lang === 'ar' ? 'الواجهات والتصميم' : 'Design Surface',
              href: docHref(lang, 'design/index', meta.designAnchor),
            },
          ],
          system: [
            {
              label: stageId.toUpperCase(),
              href: `${urlPrefix}/system/lifecycle#${stageId}`,
            },
          ],
          recommendations: recLinks,
        };

        out.push(`### ${taskId} — ${fr.title}`);
        out.push('');
        out.push(
          `<TaskCard id=${JSON.stringify(taskId)} title=${JSON.stringify(fr.title)} phase=${JSON.stringify(phase.id)} module=${JSON.stringify(mod)} priority=${JSON.stringify(fr.priority)} surfaces={${JSON.stringify(lang === 'ar' ? meta.surfacesAr : meta.surfacesEn)}} tables={${JSON.stringify(tableNames)}} trace={${JSON.stringify(traceObj)}}>`,
        );
        out.push('');
        out.push(
          lang === 'ar'
            ? `**المواصفة الوظيفية ([${fr.id}](${frHref.get(fr.id)})).** ${fr.specText}`
            : `**Specification ([${fr.id}](${frHref.get(fr.id)})).** ${fr.specText}`,
        );
        out.push('');
        out.push(lang === 'ar' ? '**مخرجات التنفيذ الهندسي.**' : '**Implementation deliverables.**');
        out.push('');
        if (lang === 'en') {
          out.push(`- **Domain & API logic:** ${meta.scopeSummaryEn}`);
          if (tableNames.length > 0) {
            out.push(
              `- **Persistence & RLS:** Read/write operations on ${tableNames.map((t) => `\`${t}\``).join(', ')} enforcing base-column conventions, soft-delete filters, and tenant/role access control ([ACL](/en/docs/erd/acl)).`,
            );
          }
          out.push(
            `- **Verification & telemetry:** Automated unit/integration coverage mapped to \`${fr.id}\` acceptance scenarios ([Test Strategy](/en/docs/plan/testing)) with structured trace/audit emission.`,
          );
        } else {
          out.push(`- **منطق النطاق والواجهة البرمجية:** ${meta.scopeSummaryAr}`);
          if (tableNames.length > 0) {
            out.push(
              `- **التخزين وأمان مستوى الصفوف:** عمليات القراءة والكتابة على الجداول ${tableNames.map((t) => `\`${t}\``).join('، ')} مع الالتزام بالأعمدة الأساسية والحذف الناعم وضوابط الوصول ([ACL](/ar/docs/erd/acl)).`,
            );
          }
          out.push(
            `- **التحقق والرصد:** اختبارات آلية تغطي سيناريوهات قبول \`${fr.id}\` وفق [استراتيجية الاختبار](/ar/docs/plan/testing) مع تسجيل الأحداث والآثار.`,
          );
        }
        out.push('');

        if (fr.acceptance.length > 0) {
          out.push(lang === 'ar' ? '**معايير القبول.**' : '**Acceptance criteria.**');
          out.push('');
          for (const acc of fr.acceptance) out.push(`- ${acc}`);
          out.push('');
        }

        // Inline Business Context (BRs + BRLs + ODs)
        const contextBullets: string[] = [];
        for (const br of fr.brs) {
          const txt = brText.get(br);
          if (txt) {
            contextBullets.push(
              `[${br}](${docHref(lang, 'brd/requirements', meta.brdAnchor)}): ${txt}`,
            );
          }
        }
        for (const brl of fr.brls) {
          const txt = brlText.get(brl);
          if (txt) {
            contextBullets.push(`[${brl}](${urlPrefix}/brd/requirements#${brlAnchor}): ${txt}`);
          }
        }
        for (const od of fr.ods) {
          const info = odInfo.get(od);
          contextBullets.push(
            lang === 'ar'
              ? `[${od}](${urlPrefix}/brd/compliance#${odSectionAnchor}) (${info ? `${info.topic} — ${info.status}` : 'قرار مفتوح'}) ← راجع [التوصية المقترحة](${urlPrefix}/recommendations/open-decisions#${odAnchorFor(docRoot, od)}).`
              : `[${od}](${urlPrefix}/brd/compliance#${odSectionAnchor}) (${info ? `${info.topic} — ${info.status}` : 'Open Decision'}) → see [Recommended Resolution](${urlPrefix}/recommendations/open-decisions#${odAnchorFor(docRoot, od)}).`,
          );
        }

        if (contextBullets.length > 0) {
          out.push(lang === 'ar' ? '**التتبع التجاري وقواعد العمل الحاكمة.**' : '**Business trace & governing rules.**');
          out.push('');
          for (const cb of contextBullets) out.push(`- ${cb}`);
          out.push('');
        }

        out.push('</TaskCard>');
        out.push('');
      }
    }

    // Decisions to close section (kept consistent: if sortedOds > 0 emit enriched table)
    if (sortedOds.length > 0) {
      out.push(lang === 'ar' ? '## القرارات المطلوب إغلاقها' : '## Decisions to close');
      out.push('');
      out.push(
        lang === 'ar'
          ? 'تُشير متطلبات هذه المرحلة إلى القرارات المفتوحة التالية — يُنصح باعتماد التوصية المقترحة لكل قرار قبل بدء بناء المرحلة:'
          : "These open decisions are cited by this phase's requirements — confirm or adopt each recommended resolution before starting the phase:",
      );
      out.push('');
      out.push(
        lang === 'ar'
          ? '| المعرّف | الموضوع | الحالة الحالية | المالك | الحل الموصى به |'
          : '| ID | Topic | Current Status | Owner | Recommended Resolution |',
      );
      out.push('|---|---|---|---|---|');
      for (const od of sortedOds) {
        const info = odInfo.get(od);
        out.push(
          `| [${od}](${urlPrefix}/brd/compliance#${odSectionAnchor}) | ${info?.topic ?? od} | ${info?.status ?? 'Open'} | ${info?.owner ?? 'Product'} | [${lang === 'ar' ? `حسم ${od}` : `Resolve ${od}`}](${urlPrefix}/recommendations/open-decisions#${odAnchorFor(docRoot, od)}) |`,
        );
      }
      out.push('');
    }

    writeFileSync(join(docRoot, 'plan', `${phase.id.toLowerCase()}.mdx`), out.join('\n') + '\n');

    const odCell =
      sortedOds
        .map((od) => `[${od}](${urlPrefix}/recommendations/open-decisions#${odAnchorFor(docRoot, od)})`)
        .join(', ') || '—';
    milestoneRows.push(
      `| [**${phase.id}** — ${shortTitle}](${urlPrefix}/plan/${phase.id.toLowerCase()}) | **${totalPhaseTasks}** (${inPhase.length} FR: ${counts.Must}M / ${counts.Should}S / ${counts.Could}C + ${phase.platformTasks.length} PLAT) | ${done} | ${odCell} |`,
    );
  }

  // Emit Milestones page (identical heading count between EN and AR: 2 `##` headings: `## The ladder` + `## Reading the ladder`)
  const milestonesTrace = {
    brd: [{ label: lang === 'ar' ? 'مراحل التسليم §3.3' : 'BRD §3.3 Phases', href: docHref(lang, 'brd/index', '33-delivery-phases') }],
    srs: [{ label: lang === 'ar' ? 'مصفوفة التتبع' : 'SRS Traceability', href: `${urlPrefix}/srs/traceability` }],
    erd: [{ label: lang === 'ar' ? 'نموذج البيانات' : 'Data Model', href: `${urlPrefix}/erd` }],
    plan: [
      { label: lang === 'ar' ? 'نظرة عامة على الخطة' : 'Plan Overview', href: `${urlPrefix}/plan` },
      { label: lang === 'ar' ? 'استراتيجية الاختبار' : 'Test Strategy', href: `${urlPrefix}/plan/testing` },
    ],
    workflows: [{ label: lang === 'ar' ? 'سير عمل النظام' : 'System Workflows', href: `${urlPrefix}/workflows` }],
    architecture: [{ label: lang === 'ar' ? 'المعمارية' : 'Architecture', href: `${urlPrefix}/architecture` }],
    design: [{ label: lang === 'ar' ? 'نظام التصميم' : 'Design System', href: `${urlPrefix}/design` }],
    system: [{ label: lang === 'ar' ? 'النظام الشامل' : 'End-to-End System', href: `${urlPrefix}/system` }],
    recommendations: [{ label: lang === 'ar' ? 'سجل التوصيات' : 'Recommendations Hub', href: `${urlPrefix}/recommendations` }],
  };

  const msOut: string[] = [];
  if (lang === 'en') {
    msOut.push('---');
    msOut.push('title: Milestones');
    msOut.push('description: The six-phase ladder from foundation to scale — 231 tasks, definitions of done, and recommended decision resolutions gating each phase.');
    msOut.push('icon: Flag');
    msOut.push('---');
    msOut.push('');
    msOut.push(
      `Each phase closes on its **definition of done** ([BRD section 3.3](/en/docs/brd#33-delivery-phases)) once every *Must* functional task and every *Platform Foundation* task passes verification against its acceptance criteria. Task counts (**${totalFrTasks + totalPlatTasks}** total: **${totalFrTasks}** functional + **${totalPlatTasks}** platform) come directly from the [generated phase pages](/en/docs/plan).`,
    );
    msOut.push('');
    msOut.push(
      `<MindmapBlock title="Six-Phase Milestone Ladder" caption="Progression from P1 Foundation through P6 Family and Scale, showing task volume and primary outcome per milestone." trace={${JSON.stringify(milestonesTrace)}}>`,
    );
    msOut.push('');
    msOut.push('```mermaid');
    msOut.push('mindmap');
    msOut.push('  root(("Trips Enjoy — 231 Delivery Tasks"))');
    for (const p of PHASES) {
      const cnt = frs.filter((f) => phaseOf.get(f.id) === p.id).length + p.platformTasks.length;
      msOut.push(`    ${sanitizeMindmapNode(`${p.id} — ${p.shortEn}`)}`);
      msOut.push(`      ${sanitizeMindmapNode(`${cnt} tasks total`)}`);
      msOut.push(`      ${sanitizeMindmapNode(p.doneEn)}`);
    }
    msOut.push('```');
    msOut.push('');
    msOut.push('</MindmapBlock>');
    msOut.push('');
    msOut.push('## The ladder');
    msOut.push('');
    msOut.push('| Phase | Tasks (Functional M/S/C + Platform) | Definition of done | Decisions to close (Recommended Resolution) |');
    msOut.push('|---|---|---|---|');
    msOut.push(...milestoneRows);
    msOut.push('');
    msOut.push('## Reading the ladder');
    msOut.push('');
    msOut.push('- **Phase gate rule:** A phase closes only when 100% of its *Must* functional tasks and all 3 *Platform Foundation* tasks pass automated and exploratory checks under the [Test Strategy](/en/docs/plan/testing); *Should* and *Could* tasks may trail into the following phase without blocking sign-off.');
    msOut.push('- **Pre-phase decision gate:** Every open decision in the rightmost column links directly to its concrete recommended resolution in [Open Decisions & Resolutions](/en/docs/recommendations/open-decisions) — lock those decisions before writing phase code, never mid-sprint.');
    msOut.push('- **End-to-end verification:** Rehearse the phase outcome against the corresponding stages in the [End-to-End Lifecycle](/en/docs/system/lifecycle) before promoting staging to production.');
  } else {
    msOut.push('---');
    msOut.push('title: المعالم الرئيسية');
    msOut.push('description: سلّم المراحل الست من الأساس إلى التوسع — 231 مهمة وتعريفات الإتمام وحسم القرارات المفتوحة لكل مرحلة.');
    msOut.push('icon: Flag');
    msOut.push('---');
    msOut.push('');
    msOut.push(
      `تُغلق كل مرحلة بتحقيق **تعريف الإتمام** الخاص بها ([القسم 3.3 من BRD](${docHref('ar', 'brd/index', '33-delivery-phases')})) بعد اجتياز جميع المهام الوظيفية الإلزامية (*Must*) ومهام البنية المشتركة لمعايير القبول. تُحتسب الأعداد (**${totalFrTasks + totalPlatTasks}** مهمة إجمالًا: **${totalFrTasks}** وظيفية + **${totalPlatTasks}** بنية منصة) آليًا من [صفحات المراحل المولَّدة](/ar/docs/plan).`,
    );
    msOut.push('');
    msOut.push(
      `<MindmapBlock title="سلّم المعالم للمراحل الست" caption="التدرج من المرحلة الأولى (الأساس) حتى المرحلة السادسة (العائلة والتوسع) مع حجم المهام ومخرج كل مرحلة." trace={${JSON.stringify(milestonesTrace)}}>`,
    );
    msOut.push('');
    msOut.push('```mermaid');
    msOut.push('mindmap');
    msOut.push('  root(("Trips Enjoy — 231 مهمة تسليم"))');
    for (const p of PHASES) {
      const cnt = frs.filter((f) => phaseOf.get(f.id) === p.id).length + p.platformTasks.length;
      msOut.push(`    ${sanitizeMindmapNode(`${p.id} — ${p.shortAr}`)}`);
      msOut.push(`      ${sanitizeMindmapNode(`${cnt} مهمة إجمالا`)}`);
      msOut.push(`      ${sanitizeMindmapNode(p.doneAr)}`);
    }
    msOut.push('```');
    msOut.push('');
    msOut.push('</MindmapBlock>');
    msOut.push('');
    msOut.push('## سلّم المراحل');
    msOut.push('');
    msOut.push('| المرحلة | المهام (الوظيفية M/S/C + المنصة) | تعريف الإتمام | القرارات المطلوب حسمها (التوصية المقترحة) |');
    msOut.push('|---|---|---|---|');
    msOut.push(...milestoneRows);
    msOut.push('');
    msOut.push('## قراءة السلّم');
    msOut.push('');
    msOut.push('- **قاعدة إغلاق المرحلة:** لا تُغلق المرحلة إلا باجتياز جميع المهام الإلزامية (*Must*) ومهام البنية المشتركة الثلاث للاختبارات المحددة في [استراتيجية الاختبار](/ar/docs/plan/testing)؛ بينما يجوز ترحيل مهام *Should* و*Could* دون تعطيل الإغلاق.');
    msOut.push('- **بوابة القرارات المسبقة:** يرتبط كل قرار مفتوح في العمود الأخير مباشرةً بالتوصية المقترحة له في صفحة [حسم القرارات المفتوحة](/ar/docs/recommendations/open-decisions) لاعتماده قبل بدء المرحلة.');
    msOut.push('- **التحقق الشامل:** يُراجع مخرج كل مرحلة عمليًا مقابل المراحل المقابلة في [دورة حياة النظام الشاملة](/ar/docs/system/lifecycle) قبل الترقية إلى بيئة الإنتاج.');
  }

  writeFileSync(join(docRoot, 'plan/milestones.mdx'), msOut.join('\n') + '\n');

  console.log(
    `[${lang}] generated 6 phase pages + milestones.mdx: ${totalFrTasks} functional + ${totalPlatTasks} platform = ${totalFrTasks + totalPlatTasks} TaskCards (BRs=${brCount})`,
  );
}

if (import.meta.main) {
  generatePlanForLocale('en');
}
