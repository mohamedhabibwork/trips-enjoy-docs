#!/usr/bin/env bun
/**
 * Generates content/docs/workflows/coverage.mdx and
 * content/docs/ar/workflows/coverage.mdx — which user flows and workflow pages
 * cover each functional requirement in both English and Arabic.
 *
 * Re-runnable (MDX stays canonical; only coverage.mdx mirrors are written):
 *   bun scripts/coverage-matrix.ts
 *
 * Attribution: an FR cited inside a `### UF-*` section links to that flow's
 * anchor; a citation on a workflows page links to the page section. Citations
 * are counted in link text (`[FR-X](…)`) and in plain text, including explicit
 * `FR-X-01 to FR-X-05` / `FR-X-01 إلى FR-X-05` ranges.
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';

const DOCS = join(fileURLToPath(new URL('..', import.meta.url)), 'content', 'docs');
const FR_RE = /(?<![A-Z])(FR-[A-Z]+-\d+)\b/g;
const RANGE_RE = /FR-([A-Z]+)-(\d+)(?:\]\([^)]*\))?\s*(?:to|إلى|وحتى|حتى)\s*\[?FR-\1-(\d+)/g;

type FrInfo = { id: string; title: string; module: string; moduleTitle: string };

function generateCoverage(lang: 'en' | 'ar') {
  const docRoot = lang === 'ar' ? join(DOCS, 'ar') : DOCS;
  const urlPrefix = lang === 'ar' ? '/ar/docs' : '/en/docs';

  const frByModule = new Map<string, { title: string; frs: FrInfo[] }>();
  const reqFiles = readdirSync(join(docRoot, 'srs/requirements'))
    .filter((f) => f.endsWith('.mdx'))
    .sort();

  for (const f of reqFiles) {
    const module = f.replace('.mdx', '');
    const lines = readFileSync(join(docRoot, 'srs/requirements', f), 'utf8').split('\n');
    const title = (lines.find((l) => l.startsWith('title:')) ?? '').replace(/^title:\s*/, '').trim();
    const entry = { title, frs: [] as FrInfo[] };
    for (const line of lines) {
      const m = line.match(/^## (FR-[A-Z]+-\d+) — (.+)$/);
      if (m) entry.frs.push({ id: m[1], title: m[2].trim(), module, moduleTitle: title });
    }
    frByModule.set(module, entry);
  }

  const coveredBy = new Map<string, { label: string; href: string }[]>();
  const add = (fr: string, label: string, href: string) => {
    const list = coveredBy.get(fr) ?? [];
    if (!list.some((c) => c.href === href)) list.push({ label, href });
    coveredBy.set(fr, list);
  };

  function collect(file: string, relDir: string) {
    const slugger = new GithubSlugger();
    const lines = readFileSync(file, 'utf8').split('\n');
    const pageTitle = (lines.find((l) => l.startsWith('title:')) ?? '')
      .replace(/^title:\s*/, '')
      .replace(/^["']|["']$/g, '')
      .trim();
    let currentAnchor = '';
    let currentLabel = '';
    let inFence = false;

    for (const line of lines) {
      if (/^```/.test(line.trim())) {
        inFence = !inFence;
        continue;
      }
      if (inFence) continue;

      const h = line.match(/^(#{2,3})\s+(.+)$/);
      if (h) {
        const headingText = h[2].trim();
        const isUf = /^UF-[A-Z]+-\d+\./.test(headingText);
        slugger.reset();
        currentAnchor = `#${slugger.slug(headingText)}`;
        currentLabel = isUf ? headingText.match(/^(UF-[A-Z]+-\d+)/)![1] : '';
        continue;
      }
      if (line.trimStart().startsWith('<')) continue;

      const label = () => {
        if (currentLabel) return currentLabel;
        if (lang === 'ar' && pageTitle) return pageTitle;
        return relDir.split('/').pop()!;
      };
      const href = () => `${urlPrefix}/${relDir}${currentAnchor}`;

      for (const m of line.matchAll(FR_RE)) add(m[1], label(), href());
      for (const r of line.matchAll(RANGE_RE)) {
        const [, mod, from, to] = r;
        for (let n = Number(from); n <= Number(to); n += 1) {
          add(`FR-${mod}-${String(n).padStart(2, '0')}`, label(), href());
        }
      }
    }
  }

  const ufFiles = readdirSync(join(docRoot, 'user-flows'))
    .filter((f) => f.endsWith('.mdx') && f !== 'index.mdx')
    .sort();
  for (const f of ufFiles) {
    collect(join(docRoot, 'user-flows', f), `user-flows/${f.replace('.mdx', '')}`);
  }

  const wfFiles = readdirSync(join(docRoot, 'workflows'))
    .filter((f) => f.endsWith('.mdx') && f !== 'coverage.mdx' && f !== 'index.mdx')
    .sort();
  for (const f of wfFiles) {
    collect(join(docRoot, 'workflows', f), `workflows/${f.replace('.mdx', '')}`);
  }

  const out: string[] = [];
  const frSlugger = new GithubSlugger();
  const frHref = (fr: FrInfo) => {
    frSlugger.reset();
    return `${urlPrefix}/srs/requirements/${fr.module}#${frSlugger.slug(`${fr.id} — ${fr.title}`)}`;
  };

  const allFrs = [...frByModule.values()].flatMap((m) => m.frs);
  const covered = allFrs.filter((fr) => (coveredBy.get(fr.id) ?? []).length > 0);
  const pct = Math.round((covered.length / allFrs.length) * 100);

  if (lang === 'en') {
    out.push('---');
    out.push('title: Requirement Coverage');
    out.push('description: Which user flows and workflows cover each functional requirement — generated, refresh with bun scripts/coverage-matrix.ts.');
    out.push('icon: TableProperties');
    out.push('---');
    out.push('');
    out.push('<Callout type="info" title="Generated page">');
    out.push('Refresh with `bun scripts/coverage-matrix.ts` — do not edit by hand. An empty *Covered by* cell means the requirement is cited by no flow or workflow page yet.');
    out.push('</Callout>');
    out.push('');
    const summaryTailEn =
      covered.length === allFrs.length
        ? 'Every requirement across all 28 modules has explicit flow or workflow traceability.'
        : 'The rest are listed here on purpose — being visible is the first step to being covered.';
    out.push(`**${covered.length} of ${allFrs.length}** functional requirements (${pct}%) are cited by at least one [user flow](/en/docs/user-flows) or [system workflow](/en/docs/workflows) page. ${summaryTailEn}`);
    out.push('');
  } else {
    out.push('---');
    out.push('title: تغطية المتطلبات');
    out.push('description: أي تدفقات المستخدم وعمليات سير العمل تغطي كل متطلب وظيفي — صفحة مولَّدة، أعد توليدها بتنفيذ bun scripts/coverage-matrix.ts.');
    out.push('icon: TableProperties');
    out.push('---');
    out.push('');
    out.push('<Callout type="info" title="صفحة مولَّدة">');
    out.push('أعد توليدها بتنفيذ `bun scripts/coverage-matrix.ts` — لا تعدلها يدويًا. خلية *مغطى بواسطة* الفارغة تعني أن المتطلب لم يُشار إليه بعد في أي صفحة تدفق أو سير عمل.');
    out.push('</Callout>');
    out.push('');
    const summaryTailAr =
      covered.length === allFrs.length
        ? 'جميع المتطلبات الوظيفية عبر الوحدات الـ 28 مغطاة بالكامل بروابط تتبع صريحة.'
        : 'أما البقية فمدرجة هنا عمدًا — فالظهور هو الخطوة الأولى نحو التغطية.';
    out.push(`**${covered.length} من أصل ${allFrs.length}** متطلبًا وظيفيًا (${pct}%) يُشار إليه في صفحة [تدفق مستخدم](/ar/docs/user-flows) واحدة على الأقل أو في صفحة [سير عمل النظام](/ar/docs/workflows). ${summaryTailAr}`);
    out.push('');
  }

  const reqHeader = lang === 'ar' ? '| المتطلب | مغطى بواسطة |' : '| Requirement | Covered by |';

  for (const [, entry] of frByModule) {
    out.push(`## ${entry.title}`);
    out.push('');
    out.push(reqHeader);
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

  const outPath = join(docRoot, 'workflows/coverage.mdx');
  writeFileSync(outPath, out.join('\n') + '\n');

  console.log(`[${lang}] coverage.mdx written: ${covered.length}/${allFrs.length} FRs covered (${pct}%)`);
  const uncovered = allFrs.filter((fr) => !(coveredBy.get(fr.id) ?? []).length);
  if (uncovered.length > 0) {
    console.log(`[${lang}] uncovered (${uncovered.length}):`);
    for (const fr of uncovered) console.log(`  ${fr.id} — ${fr.title} [${fr.module}]`);
  }
}

generateCoverage('en');
generateCoverage('ar');
