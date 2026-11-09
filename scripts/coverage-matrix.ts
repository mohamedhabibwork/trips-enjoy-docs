#!/usr/bin/env bun
/**
 * Generates content/docs/workflows/coverage.mdx — which user flows and
 * workflow pages cover each functional requirement.
 *
 * Re-runnable (MDX stays canonical; only coverage.mdx is written):
 *   bun scripts/coverage-matrix.ts
 *
 * Attribution: an FR cited inside a `### UF-*` section links to that flow's
 * anchor; a citation on a workflows page links to the page. Citations are
 * counted in link text (`[FR-X](…)`) and in plain text.
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';

const DOCS = join(fileURLToPath(new URL('..', import.meta.url)), 'content', 'docs');
// counts citations in link text ([FR-X](…)) and in plain text; URLs are lowercase so no false hits
const FR_RE = /(?<![A-Z])(FR-[A-Z]+-\d+)\b/g;
// "FR-MOD-01 to FR-MOD-05" style ranges cover the intermediate IDs too
const RANGE_RE = /FR-([A-Z]+)-(\d+)\]\([^)]*\) to \[FR-\1-(\d+)/g;

type FrInfo = { id: string; title: string; module: string; moduleTitle: string };
const frByModule = new Map<string, { title: string; frs: FrInfo[] }>();

// ── parse FR definitions ───────────────────────────────────────────────
for (const f of readdirSync(join(DOCS, 'srs/requirements'))) {
  if (!f.endsWith('.mdx')) continue;
  const module = f.replace('.mdx', '');
  const lines = readFileSync(join(DOCS, 'srs/requirements', f), 'utf8').split('\n');
  const title = (lines.find((l) => l.startsWith('title:')) ?? '').replace('title: ', '').trim();
  const entry = { title, frs: [] as FrInfo[] };
  for (const line of lines) {
    const m = line.match(/^## (FR-[A-Z]+-\d+) — (.+)$/);
    if (m) entry.frs.push({ id: m[1], title: m[2], module, moduleTitle: title });
  }
  frByModule.set(module, entry);
}

// ── collect citations ──────────────────────────────────────────────────
// fr id -> array of { label, href }
const coveredBy = new Map<string, { label: string; href: string }[]>();
const add = (fr: string, label: string, href: string) => {
  const list = coveredBy.get(fr) ?? [];
  if (!list.some((c) => c.href === href)) list.push({ label, href });
  coveredBy.set(fr, list);
};

function collect(file: string, relDir: string) {
  const slugger = new GithubSlugger();
  const lines = readFileSync(file, 'utf8').split('\n');
  let currentAnchor = ''; // nearest preceding heading anchor on this page
  let currentLabel = '';
  for (const line of lines) {
    const h = line.match(/^(#{2,3})\s+(.+)$/);
    if (h) {
      const isUf = /^UF-[A-Z]+-\d+\./.test(h[2]);
      slugger.reset();
      currentAnchor = `#${slugger.slug(h[2])}`;
      currentLabel = isUf ? h[2].match(/^(UF-[A-Z]+-\d+)/)![1] : '';
      continue;
    }
    if (line.trimStart().startsWith('<')) continue; // JSX lines
    const label = () => currentLabel || relDir.split('/').pop()!;
    const href = () => `/en/docs/${relDir}${currentAnchor}`;
    for (const m of line.matchAll(FR_RE)) add(m[1], label(), href());
    // expand "FR-MOD-01 to FR-MOD-05" ranges to their intermediate IDs
    for (const r of line.matchAll(RANGE_RE)) {
      const [, mod, from, to] = r;
      for (let n = Number(from) + 1; n < Number(to); n += 1) {
        add(`FR-${mod}-${String(n).padStart(2, '0')}`, label(), href());
      }
    }
  }
}

for (const f of readdirSync(join(DOCS, 'user-flows'))) {
  if (f.endsWith('.mdx') && f !== 'index.mdx') {
    collect(join(DOCS, 'user-flows', f), `user-flows/${f.replace('.mdx', '')}`);
  }
}
for (const f of readdirSync(join(DOCS, 'workflows'))) {
  if (f.endsWith('.mdx') && f !== 'coverage.mdx' && f !== 'index.mdx') {
    collect(join(DOCS, 'workflows', f), `workflows/${f.replace('.mdx', '')}`);
  }
}

// ── emit coverage.mdx ──────────────────────────────────────────────────
const out: string[] = [];
const frSlugger = new GithubSlugger();
const frHref = (fr: FrInfo) => {
  frSlugger.reset();
  return `/en/docs/srs/requirements/${fr.module}#${frSlugger.slug(`${fr.id} — ${fr.title}`)}`;
};

const allFrs = [...frByModule.values()].flatMap((m) => m.frs);
const covered = allFrs.filter((fr) => (coveredBy.get(fr.id) ?? []).length > 0);
const pct = Math.round((covered.length / allFrs.length) * 100);

out.push('---');
out.push('title: Requirement Coverage');
out.push('description: Which user flows and workflows cover each functional requirement — generated, refresh with bun scripts/coverage-matrix.ts.');
out.push('icon: TableProperties');
out.push('---');
out.push('');
out.push("<Callout type=\"info\" title=\"Generated page\">");
out.push('Refresh with `bun scripts/coverage-matrix.ts` — do not edit by hand. An empty *Covered by* cell means the requirement is cited by no flow or workflow page yet.');
out.push('</Callout>');
out.push('');
out.push(`**${covered.length} of ${allFrs.length}** functional requirements (${pct}%) are cited by at least one [user flow](/en/docs/user-flows) or [system workflow](/en/docs/workflows) page. The rest are listed here on purpose — being visible is the first step to being covered.`);
out.push('');

for (const [, entry] of frByModule) {
  out.push(`## ${entry.title}`);
  out.push('');
  out.push('| Requirement | Covered by |');
  out.push('|---|---|');
  for (const fr of entry.frs) {
    const cits = coveredBy.get(fr.id) ?? [];
    const citText = cits.length
      ? cits.map((c) => `[${c.label}](${c.href})`).join(', ')
      : '**—**';
    out.push(`| [${fr.id}](${frHref(fr)}) ${fr.title} | ${citText} |`);
  }
  out.push('');
}

writeFileSync(join(DOCS, 'workflows/coverage.mdx'), out.join('\n') + '\n');

// ── report ─────────────────────────────────────────────────────────────
console.log(`coverage.mdx written: ${covered.length}/${allFrs.length} FRs covered (${pct}%)`);
const uncovered = allFrs.filter((fr) => !(coveredBy.get(fr.id) ?? []).length);
console.log(`uncovered (${uncovered.length}):`);
for (const fr of uncovered) console.log(`  ${fr.id} — ${fr.title} [${fr.module}]`);
