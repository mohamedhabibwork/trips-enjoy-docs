#!/usr/bin/env bun
/**
 * link-ids-ar.ts — post-translation linker for the Arabic mirror.
 *
 * AR pages keep their EN link targets during translation (`/en/docs/...`,
 * EN anchors), so translators never touch URLs. This script rewrites them
 * mechanically:
 *
 *   ](/en/docs/<path>#<en-anchor>)  →  ](/ar/docs/<path>#<ar-anchor>)
 *   href="/en/docs/<path>…"          →  href="/ar/docs/<path>…"
 *   ](#<same-page-anchor>)           →  ](#<ar-anchor>)
 *
 * The EN→AR anchor map is built by pairing each page's headings positionally
 * (translators preserved heading structure; count mismatches are reported
 * and those pages stay linked to /en/ instead of being guessed). Slugs are
 * computed with github-slugger — the same slugger fumadocs renders with.
 *
 * Also emits src/lib/ar-anchors.ts for the ReqMeta component, whose BRD/NFR
 * anchors are hardcoded EN slugs at render time.
 *
 * Idempotent: a second run changes nothing.
 *
 * Run: bun scripts/link-ids-ar.ts
 */
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const DOCS = join(ROOT, 'content', 'docs');
const AR_ANCHORS_OUT = join(ROOT, 'src', 'lib', 'ar-anchors.ts');

// ── walkers ────────────────────────────────────────────────────────────

function walkMdx(dir: string, skipAr: boolean): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (skipAr && entry.name === 'ar') continue;
      out.push(...walkMdx(p, skipAr));
    } else if (entry.name.endsWith('.mdx')) {
      out.push(p);
    }
  }
  return out;
}

const rel = (p: string) => p.slice(DOCS.length + 1).replace(/\.mdx$/, '');

// ── heading extraction + slug pairing ─────────────────────────────────

function cleanHeadingText(raw: string): string {
  return raw
    .trim()
    .replace(/\s+#+\s*$/, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1');
}

function headings(file: string): string[] {
  const lines = readFileSync(file, 'utf8').split('\n');
  const heads: string[] = [];
  let fence = false;
  let inFrontmatter = lines[0]?.trim() === '---';
  lines.forEach((line, idx) => {
    if (inFrontmatter) {
      if (idx > 0 && line.trim() === '---') inFrontmatter = false;
      return;
    }
    if (/^```/.test(line.trim())) {
      fence = !fence;
      return;
    }
    if (fence) return;
    const m = line.match(/^(#{2,4})\s+(.+?)\s*$/);
    if (m) heads.push(cleanHeadingText(m[2]));
  });
  return heads;
}

function explicitComponentIds(file: string): string[] {
  const raw = readFileSync(file, 'utf8');
  const ids: string[] = [];
  const re = /<(?:TaskCard|RecommendationCard|SystemStage)\b[\s\S]*?\bid="([^"]+)"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(raw)) !== null) {
    ids.push(m[1].toLowerCase());
  }
  return ids;
}

function slugs(texts: string[]): string[] {
  const slugger = new GithubSlugger();
  return texts.map((t) => slugger.slug(t));
}

// ── pass 1: build per-page EN→AR anchor maps ──────────────────────────

/** rel → enSlug → arSlug */
const anchorMap = new Map<string, Map<string, string>>();
/** pages whose EN/AR heading counts diverge (anchors to them stay /en/) */
const mismatched = new Set<string>();

for (const enFile of walkMdx(DOCS, true)) {
  const r = rel(enFile);
  const arFile = join(DOCS, 'ar', r + '.mdx');
  if (!existsSync(arFile)) {
    console.error(`  ! no AR mirror for ${r} — links to it stay /en/`);
    continue;
  }
  const en = headings(enFile);
  const ar = headings(arFile);
  if (en.length !== ar.length) {
    console.error(
      `  ! heading count mismatch ${r}: EN ${en.length} vs AR ${ar.length} — anchors to this page NOT remapped`,
    );
    mismatched.add(r);
    continue;
  }
  const enSlugs = slugs(en);
  const arSlugs = slugs(ar);
  const map = new Map<string, string>();
  for (let i = 0; i < en.length; i++) map.set(enSlugs[i], arSlugs[i]);
  for (const cid of explicitComponentIds(enFile)) {
    if (!map.has(cid)) map.set(cid, cid);
  }
  anchorMap.set(r, map);
}

// ── pass 2: rewrite AR files ───────────────────────────────────────────

let filesChanged = 0;
let linksRewritten = 0;
let anchorsRemapped = 0;
const unresolved = new Map<string, number>();

const bump = (key: string) => {
  unresolved.set(key, (unresolved.get(key) ?? 0) + 1);
};

function processLine(line: string, label: string, ownMap?: Map<string, string>): string {
  let out = line;

  // 1. markdown links: ](/en/docs/<path>#<anchor>) or ](/en/docs/<path>)
  out = out.replace(
    /\]\(\/en\/docs\/([^)#\s]+)(#[^)\s]+)?\)/g,
    (full, path: string, anchor?: string) => {
      linksRewritten += 1;
      if (!anchor) return `](/ar/docs/${path})`;
      const map = anchorMap.get(path);
      if (!map) {
        bump(`${path}${anchor} (no map)`);
        return full;
      }
      const arAnchor = map.get(anchor.slice(1));
      if (!arAnchor) {
        bump(`${path}${anchor} (anchor missing)`);
        return full;
      }
      anchorsRemapped += 1;
      return `](/ar/docs/${path}#${arAnchor})`;
    },
  );

  // 2. href attributes: href="/en/docs/..." (Card components etc.)
  out = out.replace(
    /href="(\/en\/docs\/[^"\s]+)"/g,
    (full, target: string) => {
      linksRewritten += 1;
      const hash = target.indexOf('#');
      const path = hash === -1 ? target : target.slice(0, hash);
      const anchor = hash === -1 ? undefined : target.slice(hash + 1);
      if (!anchor) return `href="/ar/docs/${path}"`;
      const map = anchorMap.get(path);
      if (!map) {
        bump(`${path}#${anchor} (href, no map)`);
        return full;
      }
      const arAnchor = map.get(anchor);
      if (!arAnchor) {
        bump(`${path}#${anchor} (href, anchor missing)`);
        return full;
      }
      anchorsRemapped += 1;
      return `href="/ar/docs/${path}#${arAnchor}"`;
    },
  );

  // 3. same-page anchors: ](#foo) — remap via this page's own map
  if (ownMap) {
    // already-localized anchors this script itself wrote on a previous run
    const arValues = new Set(ownMap.values());
    out = out.replace(/\]\(#([^)\s]+)\)/g, (full, enAnchor: string) => {
      const arAnchor = ownMap.get(enAnchor);
      if (!arAnchor) {
        if (arValues.has(enAnchor)) {
          // already in AR form (idempotent re-run) — valid, leave as-is
          return full;
        }
        bump(`${label} #${enAnchor} (same-page, missing)`);
        return full;
      }
      anchorsRemapped += 1;
      return `](#${arAnchor})`;
    });
  }

  return out;
}

// ── main rewrite loop ──────────────────────────────────────────────────

for (const file of walkMdx(join(DOCS, 'ar'), false)) {
  const r = rel(file).replace(/^ar\//, '');
  const ownMap = anchorMap.get(r);
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
    const updated = processLine(line, r, ownMap);
    if (updated !== line) changed = true;
    return updated;
  });

  if (changed) {
    writeFileSync(file, next.join('\n'));
    filesChanged += 1;
  }
}

// ── emit src/lib/ar-anchors.ts ────────────────────────────────────────

const anchorEntries = [...anchorMap.entries()].map(([r, m]) => {
  const pairs = [...m.entries()]
    .map(([en, ar]) => `  '${en}': '${ar}',`)
    .join('\n');
  return `  '${r}': {\n${pairs}\n  },`;
});

const arAnchorsModule = `/**
 * GENERATED by scripts/link-ids-ar.ts — do not edit by hand.
 * Per page: EN heading slug → AR heading slug, for locale-aware anchors.
 * Regenerate with: bun scripts/link-ids-ar.ts
 */
export const arAnchors: Record<string, Record<string, string>> = {
${anchorEntries.join('\n')}
};
`;

writeFileSync(AR_ANCHORS_OUT, arAnchorsModule);

// ── report ─────────────────────────────────────────────────────────────

console.log(`pages with anchor maps: ${anchorMap.size}`);
console.log(
  `pages mismatched (anchors to them stay /en/): ${mismatched.size > 0 ? [...mismatched].join(', ') : 'none'}`,
);
console.log(`links rewritten: ${linksRewritten}`);
console.log(`anchors remapped: ${anchorsRemapped}`);
console.log(`files changed: ${filesChanged}`);
if (unresolved.size > 0) {
  console.log('unresolved anchors (kept as-is):');
  for (const [k, n] of [...unresolved.entries()].sort()) console.log(`  ${k} ×${n}`);
} else {
  console.log('all anchors resolved');
}
