#!/usr/bin/env bun
/**
 * Comprehensive quality gate for Trips Enjoy documentation (EN + AR):
 *  1. 1:1 EN <-> AR MDX file parity and positional heading-count parity
 *  2. Sidebar meta.json page/folder resolution across all directories
 *  3. BRD section 1 rule: zero fenced code or diagram blocks in brd/*
 *  4. Malformed nested markdown links check (e.g. [NFR-[DAT-04](...)])
 *  5. 100% internal link + #anchor resolution across Markdown, JSX hrefs, and TraceBar JSON props
 *  6. Locale purity: zero unlocalized /en/docs/ links inside content/docs/ar/
 *  7. Task completeness: 213 functional + 18 platform = 231 <TaskCard /> blocks in EN and AR
 *  8. Recommendation completeness: OD-01..OD-32, OPEN-01..OPEN-15, and 12 REC-* blueprints in EN and AR
 *  9. Mermaid v12 syntax & mindmap lexer safety across every ```mermaid block
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import DOMPurify from 'dompurify';
import GithubSlugger from 'github-slugger';
import mermaid from 'mermaid';

// Headless Bun shim for Mermaid's DOMPurify calls during syntax parsing
const dp = DOMPurify as unknown as {
  sanitize?: (x: string) => string;
  addHook?: () => void;
};
dp.sanitize ??= (x: string) => x;
dp.addHook ??= () => {};

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const DOCS = join(ROOT, 'content', 'docs');

const errors: string[] = [];
const warnings: string[] = [];

function walkFiles(dir: string, skipAr = false): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (skipAr && entry.name === 'ar') continue;
      out.push(...walkFiles(full, false));
    } else {
      out.push(full);
    }
  }
  return out;
}

/** Strip inline markdown formatting the way remark/fumadocs extracts heading text for GithubSlugger. */
function cleanHeadingText(raw: string): string {
  return raw
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .trim();
}

type PageInfo = {
  file: string;
  url: string;
  locale: 'en' | 'ar';
  relSlug: string; // e.g. "" for root index, "brd" for brd/index.mdx, "plan/p1" for plan/p1.mdx
  headings: string[];
  anchors: Set<string>;
  content: string;
};

function fileToRoute(file: string, locale: 'en' | 'ar'): { url: string; relSlug: string } {
  const baseDir = locale === 'en' ? DOCS : join(DOCS, 'ar');
  const rel = file.slice(baseDir.length + 1).replace(/\.mdx$/, '');
  const relSlug = rel === 'index' ? '' : rel.replace(/\/index$/, '');
  const url = relSlug === '' ? `/${locale}/docs` : `/${locale}/docs/${relSlug}`;
  return { url, relSlug };
}

function parsePage(file: string, locale: 'en' | 'ar'): PageInfo {
  const content = readFileSync(file, 'utf8');
  const lines = content.split('\n');
  const { url, relSlug } = fileToRoute(file, locale);

  const headings: string[] = [];
  const anchors = new Set<string>();
  const pageSlugger = new GithubSlugger();

  let inFrontmatter = lines[0]?.trim() === '---';
  let inFence = false;

  lines.forEach((line, idx) => {
    if (inFrontmatter) {
      if (idx > 0 && line.trim() === '---') inFrontmatter = false;
      return;
    }
    if (/^```/.test(line.trim())) {
      inFence = !inFence;
      return;
    }
    if (inFence) return;

    const h = line.match(/^(#{1,6})\s+(.+)$/);
    if (h) {
      const cleaned = cleanHeadingText(h[2]);
      headings.push(`${h[1]} ${cleaned}`);
      anchors.add(pageSlugger.slug(cleaned));
    }
  });

  // Explicit component IDs rendered into the DOM:
  // <TaskCard id="T-P1-AUTH-1" />, <RecommendationCard id="OD-05" />, <SystemStage id="STAGE-01" /> -> id.toLowerCase()
  for (const m of content.matchAll(/<(?:TaskCard|RecommendationCard|SystemStage)\b[^>]*?\bid="([^"]+)"/gs)) {
    anchors.add(m[1].toLowerCase());
    anchors.add(m[1]);
  }
  // Explicit HTML/JSX id="..." attributes (e.g., <div id="..."> or <TypeTable id="...">)
  for (const m of content.matchAll(/<(?!TaskCard|RecommendationCard|SystemStage)[A-Za-z0-9_]+\b[^>]*?\bid="([^"]+)"/g)) {
    anchors.add(m[1]);
  }

  return { file, url, locale, relSlug, headings, anchors, content };
}

// ── 1. Collect EN and AR pages & check 1:1 parity ─────────────────────────

const enMdxFiles = walkFiles(DOCS, true).filter((f) => f.endsWith('.mdx')).sort();
const arDir = join(DOCS, 'ar');
const arMdxFiles = existsSync(arDir)
  ? walkFiles(arDir, false).filter((f) => f.endsWith('.mdx')).sort()
  : [];

const pagesByUrl = new Map<string, PageInfo>();
const enByRel = new Map<string, PageInfo>();
const arByRel = new Map<string, PageInfo>();

for (const f of enMdxFiles) {
  const rel = f.slice(DOCS.length + 1);
  const info = parsePage(f, 'en');
  pagesByUrl.set(info.url, info);
  enByRel.set(rel, info);
}

for (const f of arMdxFiles) {
  const rel = f.slice(arDir.length + 1);
  const info = parsePage(f, 'ar');
  pagesByUrl.set(info.url, info);
  arByRel.set(rel, info);
}

for (const [rel, enPage] of enByRel) {
  const arPage = arByRel.get(rel);
  if (!arPage) {
    errors.push(`[PARITY] Missing Arabic mirror for content/docs/${rel}`);
    continue;
  }
  if (enPage.headings.length !== arPage.headings.length) {
    errors.push(
      `[HEADING PARITY] content/docs/${rel} has ${enPage.headings.length} headings vs content/docs/ar/${rel} with ${arPage.headings.length} headings`,
    );
  }
}
for (const rel of arByRel.keys()) {
  if (!enByRel.has(rel)) {
    errors.push(`[PARITY] Orphan Arabic file with no English source: content/docs/ar/${rel}`);
  }
}

// ── 2. Sidebar meta.json check ────────────────────────────────────────────

const metaFiles = walkFiles(DOCS, false).filter((f) => f.endsWith('/meta.json'));
for (const mf of metaFiles) {
  let parsed: { pages?: string[] };
  try {
    parsed = JSON.parse(readFileSync(mf, 'utf8'));
  } catch (e) {
    errors.push(`[META] Invalid JSON in ${mf}: ${String(e)}`);
    continue;
  }
  const dir = dirname(mf);
  for (const entry of parsed.pages ?? []) {
    if (entry.startsWith('---') && entry.endsWith('---')) continue;
    if (entry === '...') continue;
    const candidateMdx = join(dir, `${entry}.mdx`);
    const candidateFolder = join(dir, entry);
    if (!existsSync(candidateMdx) && !existsSync(candidateFolder)) {
      errors.push(`[SIDEBAR] ${mf} references "${entry}" which does not exist on disk`);
    }
  }
}

// ── 3. BRD §1 rule: zero code/diagram fences in brd/* ────────────────────

for (const page of pagesByUrl.values()) {
  if (page.relSlug === 'brd' || page.relSlug.startsWith('brd/')) {
    if (/^```/m.test(page.content)) {
      errors.push(`[BRD §1 RULE] Fenced code/diagram block found in ${page.file}`);
    }
  }
}

// ── 4 & 5 & 6. Link, #anchor, nested-link, and locale purity checks ──────

const SPECIAL_VALID_PATHS = new Set([
  '/llms.txt',
  '/llms-full.txt',
  '/api/search',
  '/api/mcp',
  '/en',
  '/ar',
]);

function checkHref(rawHref: string, sourcePage: PageInfo, contextLabel: string) {
  const href = rawHref.trim();
  if (!href || href.startsWith('http://') || href.startsWith('https://') || href.startsWith('mailto:')) {
    return;
  }

  // Same-page anchor
  if (href.startsWith('#')) {
    const anchor = decodeURIComponent(href.slice(1));
    if (anchor && !sourcePage.anchors.has(anchor)) {
      errors.push(`[ANCHOR] Broken same-page anchor "${href}" in ${sourcePage.file} (${contextLabel})`);
    }
    return;
  }

  const [pathNoQuery, rawHash] = href.split('#');
  const cleanPath = pathNoQuery.split('?')[0].replace(/\/+$/, '') || '/';

  if (SPECIAL_VALID_PATHS.has(cleanPath)) return;

  // Markdown endpoint e.g. /en/docs/srs/requirements/auth.md
  if (cleanPath.endsWith('.md') && /^\/(en|ar)\/docs\//.test(cleanPath)) {
    const pageUrl = cleanPath.slice(0, -3);
    if (!pagesByUrl.has(pageUrl)) {
      errors.push(`[MD ENDPOINT] Broken .md route "${href}" in ${sourcePage.file}`);
    }
    return;
  }

  // Locale purity check on /ar/ pages
  if (sourcePage.locale === 'ar' && cleanPath.startsWith('/en/docs')) {
    errors.push(`[LOCALE PURITY] Unlocalized English link "${href}" found in Arabic file ${sourcePage.file}`);
  }

  if (cleanPath.startsWith('/en/docs') || cleanPath.startsWith('/ar/docs')) {
    const target = pagesByUrl.get(cleanPath);
    if (!target) {
      errors.push(`[BROKEN PAGE LINK] "${href}" in ${sourcePage.file} (${contextLabel}) -> page "${cleanPath}" not found`);
      return;
    }
    if (rawHash) {
      const decodedHash = decodeURIComponent(rawHash);
      if (!target.anchors.has(decodedHash) && !target.anchors.has(rawHash)) {
        errors.push(
          `[BROKEN ANCHOR] "${href}" in ${sourcePage.file} (${contextLabel}) -> anchor "#${decodedHash}" not found on ${target.url}`,
        );
      }
    }
  }
}

for (const page of pagesByUrl.values()) {
  const lines = page.content.split('\n');
  let inFence = false;
  lines.forEach((line, idx) => {
    if (/^```/.test(line.trim())) {
      inFence = !inFence;
      return;
    }
    if (inFence) return;

    // Malformed nested markdown link e.g. [NFR-[DAT-04](...)](...)
    if (/\[[^\]]*\[[^\]]+\]\([^)]+\)[^\]]*\]\([^)]+\)/.test(line)) {
      errors.push(`[NESTED LINK] Malformed nested markdown link at ${page.file}:${idx + 1}`);
    }

    // Standard markdown links (skipping inline code spans)
    const strippedCode = line.replace(/`[^`]+`/g, '');
    for (const m of strippedCode.matchAll(/\[[^\]]+\]\(([^)\s]+)\)/g)) {
      checkHref(m[1], page, `line ${idx + 1}`);
    }
  });

  // JSX href="..." and JSON prop "href": "..." / href: "..."
  for (const m of page.content.matchAll(/\bhref\s*[:=]\s*["']([^"']+)["']/g)) {
    checkHref(m[1], page, 'JSX/TraceBar href');
  }
}

// ── 7. Task completeness (213 FR + 18 PLAT = 231 per locale) ─────────────

function checkPlanTasks(locale: 'en' | 'ar') {
  let totalCards = 0;
  let platCards = 0;
  const seenTaskIds = new Set<string>();
  const seenFrs = new Set<string>();

  for (let p = 1; p <= 6; p++) {
    const url = `/${locale}/docs/plan/p${p}`;
    const page = pagesByUrl.get(url);
    if (!page) {
      errors.push(`[PLAN] Missing phase page ${url}`);
      continue;
    }
    const cards = [...page.content.matchAll(/<TaskCard\b([\s\S]*?)>([\s\S]*?)<\/TaskCard>/g)];
    for (const card of cards) {
      totalCards++;
      const attrs = card[1];
      const body = card[2].trim();
      const idMatch = attrs.match(/\bid="([^"]+)"/);
      const id = idMatch?.[1] ?? 'UNKNOWN';
      if (seenTaskIds.has(id)) {
        errors.push(`[PLAN ${locale.toUpperCase()}] Duplicate TaskCard id "${id}" on ${url}`);
      }
      seenTaskIds.add(id);
      if (id.includes('-PLAT-')) platCards++;
      if (body.length < 80) {
        errors.push(`[PLAN ${locale.toUpperCase()}] TaskCard "${id}" on ${url} has thin/empty inline body (${body.length} chars)`);
      }
      for (const reqPillar of ['brd', 'srs', 'erd', 'plan', 'workflows', 'architecture', 'design']) {
        if (!new RegExp(`["']?${reqPillar}["']?\\s*:`).test(attrs)) {
          errors.push(`[PLAN ${locale.toUpperCase()}] TaskCard "${id}" missing "${reqPillar}" in trace prop`);
        }
      }
    }
    for (const fr of page.content.matchAll(/\b(FR-[A-Z]+-\d+)\b/g)) {
      seenFrs.add(fr[1]);
    }
  }

  if (totalCards !== 231) {
    errors.push(`[PLAN ${locale.toUpperCase()}] Expected 231 <TaskCard /> blocks (213 FR + 18 PLAT), found ${totalCards} (PLAT=${platCards})`);
  }
  if (platCards !== 18) {
    errors.push(`[PLAN ${locale.toUpperCase()}] Expected 18 platform <TaskCard /> blocks, found ${platCards}`);
  }
  if (seenFrs.size < 213) {
    errors.push(`[PLAN ${locale.toUpperCase()}] Expected all 213 FRs in phase pages, found ${seenFrs.size}`);
  }
}

checkPlanTasks('en');
checkPlanTasks('ar');

// ── 8. Recommendation completeness (OD-01..32, OPEN-01..15, 12 REC-*) ────

for (const locale of ['en', 'ar'] as const) {
  const odPage = pagesByUrl.get(`/${locale}/docs/recommendations/open-decisions`);
  if (!odPage) {
    errors.push(`[REC] Missing /${locale}/docs/recommendations/open-decisions`);
  } else {
    for (let i = 1; i <= 32; i++) {
      const id = `od-${String(i).padStart(2, '0')}`;
      if (!odPage.anchors.has(id)) {
        errors.push(`[REC ${locale.toUpperCase()}] Missing RecommendationCard anchor #${id} in open-decisions.mdx`);
      }
    }
    for (let i = 1; i <= 15; i++) {
      const id = `open-${String(i).padStart(2, '0')}`;
      if (!odPage.anchors.has(id)) {
        errors.push(`[REC ${locale.toUpperCase()}] Missing RecommendationCard anchor #${id} in open-decisions.mdx`);
      }
    }
  }

  const archRecPage = pagesByUrl.get(`/${locale}/docs/recommendations/architecture-and-ops`);
  if (!archRecPage) {
    errors.push(`[REC] Missing /${locale}/docs/recommendations/architecture-and-ops`);
  } else {
    const expectedRecs = [
      'rec-arch-01',
      'rec-data-02',
      'rec-book-03',
      'rec-pay-04',
      'rec-tax-05',
      'rec-off-06',
      'rec-trk-07',
      'rec-med-08',
      'rec-sec-09',
      'rec-fin-10',
      'rec-ux-11',
      'rec-ops-12',
    ];
    for (const id of expectedRecs) {
      if (!archRecPage.anchors.has(id)) {
        errors.push(`[REC ${locale.toUpperCase()}] Missing RecommendationCard anchor #${id} in architecture-and-ops.mdx`);
      }
    }
  }
}

// ── 9. Mermaid v12 AST parse & mindmap validation ────────────────────────

let totalMermaid = 0;
let totalMindmaps = 0;
const VALID_HEADERS = /^(flowchart|graph|sequenceDiagram|stateDiagram-v2|stateDiagram|erDiagram|mindmap|classDiagram|xychart-beta)\b/;

for (const page of pagesByUrl.values()) {
  let blockIdx = 0;
  for (const m of page.content.matchAll(/```mermaid\n([\s\S]*?)```/g)) {
    blockIdx++;
    totalMermaid++;
    const chart = m[1].trim();
    const chartLines = chart.split('\n');
    const first = chartLines[0]?.trim() ?? '';
    if (!VALID_HEADERS.test(first)) {
      errors.push(`[MERMAID] Invalid or unsupported diagram header "${first}" in ${page.file}`);
      continue;
    }
    try {
      await mermaid.parse(chart);
    } catch (err) {
      const msg = err instanceof Error ? err.message.split('\n').slice(0, 3).join(' | ') : String(err);
      errors.push(`[MERMAID PARSE] Diagram #${blockIdx} (${first}) failed mermaid.parse() in ${page.file}: ${msg}`);
    }
    if (first.startsWith('mindmap')) {
      totalMindmaps++;
      let rootCount = 0;
      for (let i = 1; i < chartLines.length; i++) {
        const raw = chartLines[i];
        if (!raw.trim()) continue;
        if (raw.includes('\t')) {
          errors.push(`[MINDMAP] Tab character used in mindmap indentation in ${page.file}`);
        }
        const indent = raw.match(/^ */)?.[0].length ?? 0;
        const text = raw.trim();
        if (indent <= 2 && i === 1) rootCount++;
        const unwrapped = text
          .replace(/^[A-Za-z0-9_-]*\(\("([^"]+)"\)\)$/, '$1')
          .replace(/^[A-Za-z0-9_-]*\("([^"]+)"\)$/, '$1')
          .replace(/^[A-Za-z0-9_-]*\["([^"]+)"\]$/, '$1')
          .replace(/^[A-Za-z0-9_-]*\{\{"([^"]+)"\}\}$/, '$1');

        if (unwrapped === text && /[()[\]:]/.test(text)) {
          errors.push(
            `[MINDMAP LEXER] Unquoted special character in mindmap node "${text}" in ${page.file} (breaks Mermaid v12 mindmap parser)`,
          );
        }
      }
      if (rootCount === 0) {
        warnings.push(`[MINDMAP] Mindmap in ${page.file} may lack a root node on line 2`);
      }
    }
  }
}

// ── 10. Static Prerender Output Integrity (.vercel/output/static) ────────

const requireStatic = process.argv.includes('--require-static');
const STATIC_DIR = join(ROOT, '.vercel', 'output', 'static');

if (requireStatic && !existsSync(STATIC_DIR)) {
  errors.push(`[STATIC BUILD] Missing ${STATIC_DIR} while --require-static was requested`);
}

let checkedStaticHtml = 0;
if (existsSync(STATIC_DIR)) {
  const expectedRoutes = ['/en', '/ar', ...pagesByUrl.keys()];
  for (const route of expectedRoutes) {
    const htmlPath = join(STATIC_DIR, route.slice(1), 'index.html');
    if (!existsSync(htmlPath)) {
      errors.push(`[STATIC BUILD] Prerendered HTML missing for route ${route} (${htmlPath})`);
      continue;
    }
    const buf = readFileSync(htmlPath);
    if (buf.length === 0) {
      errors.push(`[STATIC BUILD] Zero-byte prerendered HTML for route ${route} (${htmlPath})`);
      continue;
    }
    if (buf.includes(0)) {
      errors.push(`[STATIC BUILD] Corrupted NUL-byte (0x00) prerendered HTML for route ${route} (${htmlPath})`);
      continue;
    }
    const headStr = buf.subarray(0, 64).toString('utf8').trimStart();
    if (!/^<!doctype html>/i.test(headStr)) {
      errors.push(`[STATIC BUILD] Invalid HTML header for route ${route}: "${headStr.slice(0, 32)}"`);
      continue;
    }
    checkedStaticHtml++;
  }
}

// ── Summary report ───────────────────────────────────────────────────────

console.log(`Checked ${pagesByUrl.size} MDX pages (${enByRel.size} EN + ${arByRel.size} AR)`);
console.log(`Checked ${totalMermaid} Mermaid diagrams via mermaid.parse() (${totalMindmaps} mindmaps)`);
if (existsSync(STATIC_DIR)) {
  console.log(`Checked ${checkedStaticHtml} prerendered static HTML routes (EN + AR)`);
}
if (warnings.length > 0) {
  console.warn(`Warnings (${warnings.length}):`);
  for (const w of warnings) console.warn(`  ⚠ ${w}`);
}
if (errors.length > 0) {
  console.error(`\nFAILED with ${errors.length} error(s):`);
  for (const err of errors.slice(0, 60)) console.error(`  ✗ ${err}`);
  if (errors.length > 60) console.error(`  ... and ${errors.length - 60} more errors`);
  process.exit(1);
}
console.log('✓ ALL DOCS QUALITY GATES PASSED!');
