#!/usr/bin/env bun
/**
 * Generates the phased Delivery Plan (content/docs/plan/p1..p6.mdx and
 * milestones.mdx) from the living docs:
 *
 *   FR  ← module pages (title + <ReqMeta priority source>)
 *   BR  ← brd/requirements.mdx §6 rows (Phase column, P1..P6)
 *   FR→phase = earliest phase among the BRs its ReqMeta cites; FRs citing no
 *   BR fall back to the traceability matrix §10.1 (BR→FR lists)
 *   covering flows/workflows = citation collection shared with coverage-matrix
 *
 * Re-runnable: bun scripts/generate-plan.ts (overwrites the generated pages;
 * index.mdx and testing.mdx are hand-written and untouched).
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';

const DOCS = join(fileURLToPath(new URL('..', import.meta.url)), 'content', 'docs');

// ── phase metadata (BRD §3.3, verbatim themes and gates) ────────────────

const PHASES: { id: string; theme: string; done: string; cross: string[] }[] = [
  {
    id: 'P1',
    theme: 'Foundation — accounts, organization applications and approval, tenancy',
    done: 'An organization can apply, be approved and sign in to an empty workspace.',
    cross: [
      'CI pipeline and preview environments',
      'Tenancy foundation: Postgres schemas, row-level security policies, base columns',
      'Observability baseline: logs, metrics, traces and error tracking',
    ],
  },
  {
    id: 'P2',
    theme: 'Single-vehicle pilot (Egypt) — trip creation, traveler booking, payment in EGP, admin basics',
    done: 'One pilot organization sells one trip to real travelers with real payments.',
    cross: [
      'Egypt payment gateway integration and callback handling',
      'Pilot environment and seed data',
      'Email and SMS provider integration for transactional messages',
    ],
  },
  {
    id: 'P3',
    theme: 'Operational depth — multi-vehicle trips, programs, seat modes, moderator app, check-in',
    done: 'A three-bus trip with about 120 passengers runs end to end.',
    cross: [
      'Moderator installable app build with the offline sync layer',
      'Multi-vehicle operational tooling and manifests',
      'Check-in device fleet testing (low connectivity, queue replay)',
    ],
  },
  {
    id: 'P4',
    theme: 'Marketplace supply — platform drivers and photographers, lab tests, negotiation, media',
    done: 'Organizations book platform suppliers through deals and deliver albums with all privacy modes.',
    cross: [
      'Media processing pipeline: variants, EXIF stripping, watermarking',
      'Stripe Connect Express onboarding for suppliers',
      'Lab-test document handling and expiry tracking',
    ],
  },
  {
    id: 'P5',
    theme: 'Monetization and Gulf launch — plans for all roles, feature gates, coupons, full accounting, invoices; Saudi Arabia (SAR) and UAE (AED)',
    done: 'Recurring revenue is measurable, limits are enforced, and the Gulf markets are live once OD-10 is decided.',
    cross: [
      'Plan service and server-side feature gates',
      'Invoice numbering service with retries and finance review',
      'Gulf market configuration (SAR, AED) once OD-10 is decided',
    ],
  },
  {
    id: 'P6',
    theme: 'Family and scale — live tracking, parent tracker, analytics, public API',
    done: 'Parents see their children live; organizations get analytics and integrations.',
    cross: [
      'WebSocket channel with SSE fallback for live data',
      'Public API gateway: keys, rate limits, OpenAPI publication',
      'Analytics warehouse and export pipeline',
    ],
  },
];

const MODULE_ORDER = [
  'AUTH', 'ONB', 'ORG', 'EMP', 'TRIP', 'PRG', 'FLT', 'STF', 'NEG', 'SEAT',
  'BKG', 'PAY', 'CPN', 'SUB', 'CHK', 'MOD', 'TRK', 'PAR', 'MED', 'PHO',
  'DRV', 'ADM', 'ACC', 'NTF', 'TRV', 'MKT', 'ANL', 'API',
];

// ── parse FRs (title + ReqMeta) ────────────────────────────────────────

type Fr = { id: string; title: string; module: string; moduleTitle: string; priority: string; brs: string[]; ods: string[] };
const frs: Fr[] = [];
const frSlug = new GithubSlugger();

for (const f of readdirSync(join(DOCS, 'srs/requirements'))) {
  if (!f.endsWith('.mdx')) continue;
  const module = f.replace('.mdx', '').toUpperCase();
  const lines = readFileSync(join(DOCS, 'srs/requirements', f), 'utf8').split('\n');
  const moduleTitle = (lines.find((l) => l.startsWith('title:')) ?? '').replace('title: ', '').trim();
  for (let i = 0; i < lines.length; i++) {
    const h = lines[i].match(/^## (FR-[A-Z]+-\d+) — (.+)$/);
    if (!h) continue;
    // house format: heading, shall-statement, then the ReqMeta line
    const metaLine = lines.slice(i + 1, i + 4).find((l) => l.includes('<ReqMeta')) ?? '';
    const meta = metaLine.match(/<ReqMeta priority="(\w+)" source="([^"]+)" \/>/);
    const source = meta?.[2] ?? '';
    const ids = source.split(';').map((s) => s.trim()).filter(Boolean);
    frs.push({
      id: h[1],
      title: h[2],
      module,
      moduleTitle,
      priority: meta?.[1] ?? 'Must',
      brs: ids.filter((id) => /^BR-/.test(id)),
      ods: ids.filter((id) => /^OD-/.test(id)),
    });
  }
}

const frHref = new Map<string, string>();
for (const fr of frs) {
  frSlug.reset();
  frHref.set(fr.id, `/en/docs/srs/requirements/${fr.module.toLowerCase()}#${frSlug.slug(`${fr.id} — ${fr.title}`)}`);
}

// ── parse BR phases ────────────────────────────────────────────────────

const brPhase = new Map<string, string>(); // BR -> earliest Pn
{
  const lines = readFileSync(join(DOCS, 'brd/requirements.mdx'), 'utf8').split('\n');
  for (const line of lines) {
    const m = line.match(/^\| \[BR-[A-Z]+-\d+\]\([^)]*\) \| .+ \| [MSC] \| (.+) \|$/);
    if (!m) continue;
    const id = line.match(/\[BR-[A-Z]+-\d+\]/)![0].slice(1, -1);
    const phases = [...m[1].matchAll(/P\d/g)].map((p) => p[0]);
    if (phases.length === 0) continue;
    phases.sort();
    brPhase.set(id, phases[0]); // dual-phase BRs land in their earliest phase
  }
}

// fallback: FRs citing no BR — invert the §10.1 matrix
const frPhaseFallback = new Map<string, string>();
{
  const lines = readFileSync(join(DOCS, 'srs/traceability.mdx'), 'utf8').split('\n');
  for (const line of lines) {
    if (!/^\| \[BR-[A-Z]+-\d+\]\(/.test(line)) continue;
    const br = line.match(/\[BR-[A-Z]+-\d+\]/)![0].slice(1, -1);
    const phase = brPhase.get(br);
    if (!phase) continue;
    for (const fr of line.matchAll(/\[(FR-[A-Z]+-\d+)\]\(/g)) {
      if (!frPhaseFallback.has(fr[1])) frPhaseFallback.set(fr[1], phase);
    }
  }
}

// ── covering flows/workflows (same attribution as coverage-matrix) ─────

const coveredBy = new Map<string, { label: string; href: string }[]>();
function collectCitations(file: string, relDir: string) {
  const slugger = new GithubSlugger();
  let anchor = '';
  let label = '';
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const h = line.match(/^(#{2,3})\s+(.+)$/);
    if (h) {
      slugger.reset();
      anchor = `#${slugger.slug(h[2])}`;
      label = /^UF-[A-Z]+-\d+\./.test(h[2]) ? h[2].match(/^(UF-[A-Z]+-\d+)/)![1] : '';
      continue;
    }
    if (line.trimStart().startsWith('<')) continue;
    for (const m of line.matchAll(/(?<![A-Z])(FR-[A-Z]+-\d+)\b/g)) {
      const l = label || relDir.split('/').pop()!;
      const list = coveredBy.get(m[1]) ?? [];
      if (!list.some((c) => c.href === `/en/docs/${relDir}${anchor}`)) {
        list.push({ label: l, href: `/en/docs/${relDir}${anchor}` });
      }
      coveredBy.set(m[1], list);
    }
  }
}
for (const f of readdirSync(join(DOCS, 'user-flows'))) {
  if (f.endsWith('.mdx') && f !== 'index.mdx') collectCitations(join(DOCS, 'user-flows', f), `user-flows/${f.replace('.mdx', '')}`);
}
for (const f of readdirSync(join(DOCS, 'workflows'))) {
  if (f.endsWith('.mdx') && !['coverage.mdx', 'index.mdx'].includes(f)) {
    collectCitations(join(DOCS, 'workflows', f), `workflows/${f.replace('.mdx', '')}`);
  }
}

// ── assign every FR to a phase ─────────────────────────────────────────

const phaseOf = new Map<string, string>(); // FR id -> Pn
const unassigned: string[] = [];
for (const fr of frs) {
  const phases = fr.brs.map((br) => brPhase.get(br)).filter(Boolean) as string[];
  if (phases.length > 0) {
    phases.sort();
    phaseOf.set(fr.id, phases[0]);
  } else if (frPhaseFallback.has(fr.id)) {
    phaseOf.set(fr.id, frPhaseFallback.get(fr.id)!);
  } else {
    unassigned.push(fr.id);
  }
}

// BRL-only FRs (no BR trace, absent from §10.1) inherit their module's
// earliest phase so they stay with their epic
for (const id of unassigned) {
  const fr = frs.find((f) => f.id === id)!;
  const modulePhases = frs
    .filter((f) => f.module === fr.module && phaseOf.has(f.id))
    .map((f) => phaseOf.get(f.id)!)
    .sort();
  if (modulePhases.length === 0) continue;
  phaseOf.set(id, modulePhases[0]);
}
const stillUnassigned = unassigned.filter((id) => !phaseOf.has(id));

// ── emit phase pages ───────────────────────────────────────────────────

let totalTasks = 0;
const milestoneRows: string[] = [];

for (const phase of PHASES) {
  const inPhase = frs.filter((fr) => phaseOf.get(fr.id) === phase.id);
  const byModule = new Map<string, Fr[]>();
  for (const fr of inPhase) {
    const list = byModule.get(fr.module) ?? [];
    list.push(fr);
    byModule.set(fr.module, list);
  }
  const orderedModules = MODULE_ORDER.filter((m) => byModule.has(m));

  const out: string[] = [];
  out.push('---');
  out.push(`title: ${phase.id} — ${phase.theme.split(' — ')[0]}`);
  out.push(`description: "Delivery tasks for ${phase.id}: ${phase.theme.split(' — ')[1] ?? phase.theme}, generated from the requirement trace."`);
  out.push('icon: Milestone');
  out.push('---');
  out.push('');
  out.push(`**Theme.** ${phase.theme}`);
  out.push('');
  out.push(`**Definition of done.** ${phase.done} *(BRD section 3.3)*`);
  out.push('');
  out.push(`**${inPhase.length} tasks** from ${orderedModules.length} modules. Tasks are generated from the requirement trace: every task implements one functional requirement and links to it; *Covered by* names the [user flows](/en/docs/user-flows) and [system workflows](/en/docs/workflows) that exercise it.`);
  out.push('');

  // cross-cutting epic
  out.push('## Cross-cutting');
  out.push('');
  out.push('| Task | Area |');
  out.push('|---|---|');
  for (const c of phase.cross) out.push(`| ${c} | Platform |`);
  out.push('');

  // module epics
  for (const mod of orderedModules) {
    const list = byModule.get(mod)!;
    const title = list[0].moduleTitle.replace(/^[A-Z]+ — /, '');
    out.push(`## ${mod} — ${title}`);
    out.push('');
    out.push('| Task | Requirement | Priority | Covered by |');
    out.push('|---|---|---|---|');
    let seq = 0;
    for (const fr of list) {
      seq += 1;
      totalTasks += 1;
      const cits = (coveredBy.get(fr.id) ?? []).slice(0, 4).map((c) => `[${c.label}](${c.href})`).join(', ');
      out.push(
        `| T-${phase.id}-${mod}-${seq} — ${fr.title} | [${fr.id}](${frHref.get(fr.id)}) | ${fr.priority} | ${cits || '—'} |`,
      );
    }
    out.push('');
  }

  // open decisions gating this phase
  const ods = new Set<string>();
  for (const fr of inPhase) for (const od of fr.ods) ods.add(od);
  if (ods.size > 0) {
    out.push('## Decisions to close');
    out.push('');
    out.push('These open decisions are cited by this phase\'s requirements:');
    out.push('');
    for (const od of [...ods].sort()) out.push(`- [${od}](/en/docs/brd/compliance#11-open-decisions)`);
    out.push('');
  }

  writeFileSync(join(DOCS, 'plan', `${phase.id.toLowerCase()}.mdx`), out.join('\n') + '\n');

  const counts = { Must: 0, Should: 0, Could: 0 } as Record<string, number>;
  for (const fr of inPhase) counts[fr.priority] += 1;
  const odLinks = [...ods].sort().map((od) => `[${od}](/en/docs/brd/compliance#11-open-decisions)`).join(', ') || '—';
  milestoneRows.push(
    `| **${phase.id}** — ${phase.theme.split(' — ')[0]} | ${inPhase.length} (${counts.Must}M / ${counts.Should}S / ${counts.Could}C) | ${phase.done} | ${odLinks} |`,
  );
}

// ── emit milestones ────────────────────────────────────────────────────

const milestones = [
  '---',
  'title: Milestones',
  'description: The phase ladder from foundation to scale — tasks per phase, definitions of done and the open decisions that gate each phase.',
  'icon: Flag',
  '---',
  '',
  'Each phase closes on its **definition of done** (BRD section 3.3) with every *Must* task verified against its acceptance criteria. Task counts come from the [generated phase pages](/en/docs/plan); re-run `bun scripts/generate-plan.ts` after requirement changes to refresh them.',
  '',
  '## The ladder',
  '',
  '| Phase | Tasks (M/S/C) | Definition of done | Decisions to close |',
  '|---|---|---|---|',
  ...milestoneRows,
  '',
  '## Reading the ladder',
  '',
  '- A phase gate needs all of its *Must* tasks green; *Should* and *Could* may trail into the next phase without blocking it.',
  '- The decisions column lists open decisions cited by that phase\'s requirements — close them before the phase starts, not during.',
  '- Cross-cutting platform tasks (CI, environments, integrations) sit at the top of each phase page and gate the same milestone.',
].join('\n');

writeFileSync(join(DOCS, 'plan/milestones.mdx'), milestones + '\n');

// ── report ─────────────────────────────────────────────────────────────

console.log(`generated ${PHASES.length} phase pages + milestones.mdx`);
console.log(`tasks: ${totalTasks} (FRs: ${frs.length})`);
const phaseCounts = PHASES.map((p) => `${p.id}=${frs.filter((fr) => phaseOf.get(fr.id) === p.id).length}`).join(' ');
console.log(`per phase: ${phaseCounts}`);
const consumedBrs = new Set(frs.flatMap((fr) => fr.brs));
console.log(`BRs cited by tasks: ${consumedBrs.size}/${brPhase.size}`);
if (stillUnassigned.length > 0) {
  console.error(`✗ unassigned FRs: ${stillUnassigned.join(', ')}`);
  process.exit(1);
}
console.log('all FRs assigned to exactly one phase ✓');
