#!/usr/bin/env bun
/**
 * One-time cross-linker: turns bare requirement-ID citations in content/docs
 * into links, in place (MDX is canonical — this edits it directly).
 *
 * Targets (computed with github-slugger, the same slugger fumadocs uses):
 *   FR-XXX-nn → exact heading anchor on the module page
 *   UF-XXX-nn → exact heading anchor on the role flow page
 *   BR/BRL/OD/NFR/DAT → nearest-heading anchor on their defining page
 *     (generic: table-row IDs map to the closest preceding heading)
 *   BRD.md/SRS.md/ERD.md → section landing pages
 *   D-nn → deliberately NOT linked (external decision register)
 *
 * Skips: frontmatter, headings (no self-links), JSX lines (ReqMeta source is
 * linked by the component), fenced code, inline code spans.
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';

const DOCS = join(fileURLToPath(new URL('..', import.meta.url)), 'content', 'docs');

// ── tiny mdx-aware file walker ─────────────────────────────────────────

function walkMdx(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) {
      // the AR mirror manages its own links (scripts/link-ids-ar.ts) —
      // EN anchor maps must not pick up AR headings
      if (entry.name === 'ar') continue;
      out.push(...walkMdx(p));
    } else if (entry.name.endsWith('.mdx')) out.push(p);
  }
  return out;
}

const rel = (p: string) => p.slice(DOCS.length + 1).replace(/\.mdx$/, '');
const DOC_URL = (r: string) => `/en/docs/${r}`;

// ── pass 1: build the ID → href map ────────────────────────────────────

const hrefs = new Map<string, string>();

function mapHeadings(file: string, headingRe: RegExp) {
  const slugger = new GithubSlugger();
  const lines = readFileSync(file, 'utf8').split('\n');
  for (const line of lines) {
    const m = line.match(headingRe);
    if (!m) continue;
    slugger.reset(); // IDs are unique per page; anchors computed per heading
    hrefs.set(m[1], `${DOC_URL(rel(file))}#${slugger.slug(m[0].replace(/^#+\s*/, ''))}`);
  }
}

// FR (## FR-AUTH-01 — Title) and UF (### UF-TRV-01. Title): exact anchors
for (const f of walkMdx(DOCS)) {
  if (f.includes('srs/requirements')) mapHeadings(f, /^## (FR-[A-Z]+-\d+) — .+$/);
  if (f.includes('user-flows')) mapHeadings(f, /^### (UF-[A-Z]+-\d+)\. .+$/);
}

/**
 * Generic table-row mapper: every row whose first cell is an ID matching
 * `idRe` links to the closest preceding heading on that page.
 */
function mapTableRows(file: string, idRe: RegExp) {
  const slugger = new GithubSlugger();
  let anchor = '';
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const h = line.match(/^(#{2,3})\s+(.+)$/);
    if (h) {
      slugger.reset();
      anchor = `#${slugger.slug(h[2])}`;
      continue;
    }
    const row = line.match(/^\|([^|]+)\|/);
    if (row) {
      // cells may already be links from a previous linking pass — unwrap first
      const id = row[1].trim().replace(/^\[([^\]]+)\]\([^)]*\)$/, '$1');
      if (idRe.test(id)) hrefs.set(id, `${DOC_URL(rel(file))}${anchor}`);
    }
  }
}

/**
 * Step-line mapper for brd/requirements.mdx — the requirement/rules tables
 * became <Steps>/<Step> blocks, so each registry line is now
 * `**BR-XXX-nn · M · P1** — text` (or `**BRL-nn** — text`). Same contract as
 * mapTableRows: every ID maps to the closest preceding heading anchor.
 */
function mapStepLines(file: string) {
  const slugger = new GithubSlugger();
  let anchor = '';
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const h = line.match(/^(#{2,3})\s+(.+)$/);
    if (h) {
      slugger.reset();
      anchor = `#${slugger.slug(h[2])}`;
      continue;
    }
    // `**BR-XXX-nn** — …` or `**BR-XXX-nn · M · P1** — …`
    const step = line.match(/^\*\*(BR-[A-Z]+-\d+|BRL-\d+)\b/);
    if (step) hrefs.set(step[1], `${DOC_URL(rel(file))}${anchor}`);
  }
}

mapStepLines(join(DOCS, 'brd/requirements.mdx'));
mapTableRows(join(DOCS, 'brd/compliance.mdx'), /^OD-\d+$/);
mapTableRows(join(DOCS, 'srs/quality.mdx'), /^NFR-[A-Z]+-\d+$/);
mapTableRows(join(DOCS, 'srs/data.mdx'), /^DAT-\d+$/);

// document-level mentions
const docLinks: [RegExp, string][] = [
  [/\bBRD\.md\b/g, '[BRD](/en/docs/brd)'],
  [/\bSRS\.md\b/g, '[SRS](/en/docs/srs)'],
  [/\bERD\.md\b/g, '[ERD](/en/docs/erd)'],
];

// longest families first; lookbehind stops `FR-` matching inside `NFR-`,
// `D-` inside `OD-`, and re-wrapping IDs that are already link text `[FR-…`
const idLinkers: { name: string; re: RegExp }[] = [
  { name: 'FR', re: /(?<![A-Z])(?<!\[)(FR-[A-Z]+-\d+)\b/g },
  { name: 'UF', re: /(?<![A-Z])(?<!\[)(UF-[A-Z]+-\d+)\b/g },
  { name: 'BR', re: /(?<![A-Z])(?<!\[)(BR-[A-Z]+-\d+)\b/g },
  { name: 'BRL', re: /(?<![A-Z])(?<!\[)(BRL-\d+)\b/g },
  { name: 'NFR', re: /(?<![A-Z])(?<!\[)(NFR-[A-Z]+-\d+)\b/g },
  { name: 'OD', re: /(?<![A-Z])(?<!\[)(OD-\d+)\b/g },
  { name: 'DAT', re: /(?<![A-Z])(?<!\[)(DAT-\d+)\b/g },
];

// ── pass 2: rewrite files ──────────────────────────────────────────────

const stats: Record<string, number> = {};
const missing = new Map<string, number>();
const track = (name: string, id: string) => {
  stats[name] = (stats[name] ?? 0) + 1;
  if (!hrefs.has(id)) missing.set(id, (missing.get(id) ?? 0) + 1);
};

/** Link IDs in a prose segment (inline code spans already removed). */
function linkSegment(text: string): string {
  let out = text;
  for (const { name, re } of idLinkers) {
    out = out.replace(re, (full, id: string) => {
      const href = hrefs.get(id);
      track(name, id);
      return href ? `[${id}](${href})` : full;
    });
  }
  return out;
}

/** Apply fn to the parts of a line outside inline code spans. */
function outsideCodeSpans(line: string, fn: (s: string) => string): string {
  const parts = line.split(/(`[^`]*`)/);
  return parts.map((p, i) => (i % 2 === 1 ? p : fn(p))).join('');
}

let changedFiles = 0;
for (const file of walkMdx(DOCS)) {
  const lines = readFileSync(file, 'utf8').split('\n');
  const inFrontmatterStart = lines[0]?.trim() === '---';
  let inFrontmatter = inFrontmatterStart;
  let fence = false;
  let changed = false;

  const next = lines.map((line, idx) => {
    if (inFrontmatter) {
      if (idx > 0 && line.trim() === '---') inFrontmatter = false;
      return line;
    }
    if (/^```/.test(line.trim())) {
      fence = !fence;
      return line;
    }
    if (fence) return line;
    if (line.startsWith('#') || line.trimStart().startsWith('<')) return line;

    const updated = outsideCodeSpans(line, (seg) => {
      let s = linkSegment(seg);
      for (const [re, replacement] of docLinks) s = s.replace(re, replacement);
      return s;
    });
    if (updated !== line) changed = true;
    return updated;
  });

  if (changed) {
    writeFileSync(file, next.join('\n'));
    changedFiles += 1;
  }
}

// ── report ─────────────────────────────────────────────────────────────

console.log(`linkable IDs mapped: ${hrefs.size}`);
const frCount = [...hrefs.keys()].filter((k) => /^FR-/.test(k)).length;
const ufCount = [...hrefs.keys()].filter((k) => /^UF-/.test(k)).length;
console.log(`  FR anchors: ${frCount} (expect 201)`);
console.log(`  UF anchors: ${ufCount} (expect 105)`);
console.log(`\nmentions processed: ${Object.entries(stats).map(([k, v]) => `${k}=${v}`).join(' ')}`);
console.log(`files updated: ${changedFiles}`);

if (missing.size > 0) {
  console.log(`\nmentions without a target (left unlinked):`);
  for (const [id, n] of [...missing.entries()].sort()) console.log(`  ${id} ×${n}`);
}

// sanity: every mapped FR/UF anchor must exist as a heading somewhere
let broken = 0;
const headingSlugs = new Set<string>();
for (const f of walkMdx(DOCS)) {
  const slugger = new GithubSlugger();
  for (const line of readFileSync(f, 'utf8').split('\n')) {
    const h = line.match(/^(#{2,3})\s+(.+)$/);
    if (h) {
      slugger.reset();
      headingSlugs.add(`${rel(f)}#${slugger.slug(h[2])}`);
    }
  }
}
for (const [id, href] of hrefs) {
  const target = href.replace('/en/docs/', '').replace('#', '#');
  const path = href.slice('/en/docs/'.length).split('#')[0];
  const anchor = href.split('#')[1];
  if (!headingSlugs.has(`${path}#${anchor}`)) {
    console.error(`  ✗ broken anchor for ${id}: ${href}`);
    broken += 1;
  }
  void target;
}
console.log(broken === 0 ? '\nall mapped anchors resolve ✓' : `\n${broken} broken anchors ✗`);
if (broken > 0) process.exit(1);
