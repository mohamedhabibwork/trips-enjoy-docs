#!/usr/bin/env bun
/**
 * One-time migration: spec markdown (BRD/SRS/ERD/user-flows) → content/docs MDX.
 *
 * ALREADY RUN (2026-11-09). MDX under content/docs is now the canonical
 * documentation; the original .md sources live in archive/specs/. This script
 * is kept as the record of the migration — do NOT wire it into package.json
 * or CI, and note that re-running it would overwrite hand edits in content/docs.
 *
 * Transforms:
 *   - split documents into pages per a declarative map
 *   - frontmatter with title/description/icon
 *   - SRS: `**FR-MOD-NN. Title.**` → `## FR-MOD-NN — Title`;
 *          `Priority: X. Source: Y.` → `<ReqMeta priority="X" source="Y" />`
 *   - ERD: standalone bold entity lines → `## entity` headings
 *   - flows: numbered step runs → `<Steps>/<Step>`
 *   - doc-control tables → `<Callout>` banner
 *   - mermaid fence bodies dedented (rendered by remarkMdxMermaid at compile)
 *   - fidelity report: every construct count in sources must match outputs
 */
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SPECS = join(ROOT, 'archive', 'specs');
const SRC = {
  brd: join(SPECS, 'BRD.md'),
  srs: join(SPECS, 'SRS.md'),
  erd: join(SPECS, 'ERD.md'),
  flows: (n: string) => join(SPECS, 'user-flows', `${n}.md`),
};
const OUT = join(ROOT, 'content', 'docs');

type Page = { path: string; body: string };
const pages: Page[] = [];

// ─────────────────────────────── helpers ───────────────────────────────

const read = (p: string) => readFileSync(p, 'utf8').split('\n');

const slug = (s: string) =>
  s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');

/** Split a document into `## ` sections. Returns entries in document order. */
function splitH2(lines: string[]) {
  const out: { title: string; body: string[] }[] = [];
  let cur: { title: string; body: string[] } | null = null;
  for (const line of lines) {
    if (/^## /.test(line)) {
      cur = { title: line.slice(3).trim(), body: [] };
      out.push(cur);
    } else if (cur) {
      cur.body.push(line);
    }
  }
  return out;
}

/** Split a section body into `### ` subsections (keeps the number in the title). */
function splitH3(body: string[]) {
  const out: { title: string; body: string[] }[] = [];
  let cur: { title: string; body: string[] } | null = null;
  for (const line of body) {
    if (/^### /.test(line)) {
      cur = { title: line.slice(4).trim(), body: [] };
      out.push(cur);
    } else if (cur) {
      cur.body.push(line);
    }
  }
  return out;
}

/** Lines before the first `## ` heading (title + doc-control table). */
function headChunk(lines: string[]) {
  const idx = lines.findIndex((l) => /^## /.test(l));
  return idx === -1 ? lines : lines.slice(0, idx);
}

function trimBody(lines: string[]) {
  // drop leading/trailing blanks and the trailing empty line artifacts
  const copy = [...lines];
  while (copy.length && copy[0].trim() === '') copy.shift();
  while (copy.length && copy.at(-1)!.trim() === '') copy.pop();
  return copy;
}

/**
 * Re-emit a source section's own `## N. Title` heading above its body.
 * splitH2 strips the heading line; multi-section pages need it back for the
 * ToC and as an anchor target (e.g. `#7-business-rules`).
 */
function withHeading(title: string, body: string[]) {
  return [`## ${title}`, '', ...trimBody(body)];
}

// ─────────────────────────────── transforms ────────────────────────────

/** First `| Field | Value |` table → compact Callout; rest of the lines pass through. */
function docControlToCallout(lines: string[]) {
  const out: string[] = [];
  let fields: [string, string][] = [];
  let inTable = false;
  let tableDone = false;

  for (const line of lines) {
    const isRow = /^\|.*\|/.test(line);
    if (!tableDone && isRow) {
      inTable = true;
      const cells = line.split('|').map((c) => c.trim());
      const isHeader = cells[1] === 'Field';
      const isSeparator = /^-+$/.test(cells[1]);
      // [|, k, v, |]
      if (cells.length >= 4 && !isHeader && !isSeparator) fields.push([cells[1], cells.slice(2, -1).join(' | ')]);
      continue;
    }
    if (inTable && !isRow) {
      inTable = false;
      tableDone = true;
      if (fields.length) {
        out.push('<Callout type="info" title="Document control">', '');
        out.push(fields.map(([k, v]) => `**${k}** ${v}`).join('  \n'));
        out.push('', '</Callout>');
      }
      fields = [];
    }
    out.push(line);
  }
  return out;
}

/** Dedent fenced code bodies (mermaid diagrams are 4-space indented). */
function dedentFences(lines: string[]) {
  let fence: string | null = null;
  let indent = 0;
  return lines.map((line) => {
    if (fence === null) {
      if (/^```/.test(line)) {
        fence = line.slice(3);
        indent = Infinity;
      }
      return line;
    }
    if (/^```/.test(line)) {
      fence = null;
      return line;
    }
    if (line.trim() !== '') indent = Math.min(indent, line.length - line.trimStart().length);
    // two-pass is overkill; the bodies use a uniform 4-space indent
    return line.replace(/^ {1,4}/, '');
  });
}

const FR_HEADING = /^\*\*(FR-[A-Z]+-\d+)\.\s*(.+?)\.?\*\*\s*(.*)$/;
const PRIORITY_LINE = /^Priority: (Must|Should|Could)\. Source: (.*?)\.?$/;

/** SRS module body: FR bold run-ins → H2 headings, priority lines → <ReqMeta>. */
function transformFRs(lines: string[]) {
  return lines.flatMap((line) => {
    const fr = line.match(FR_HEADING);
    if (fr) {
      // `**FR-X. Title.** The platform shall…` → heading line + remainder
      const heading = `## ${fr[1]} — ${fr[2]}`;
      return fr[3] ? [heading, fr[3]] : [heading];
    }
    const pr = line.match(PRIORITY_LINE);
    if (pr) return [`<ReqMeta priority="${pr[1]}" source="${pr[2]}" />`];
    return [line];
  });
}

/** ERD domain body: standalone bold entity lines (no colon, no backticks) → headings. */
const ENTITY_LINE = /^\*\*([a-z_][a-z0-9_]*)\*\*\s+(.+)$/;

function transformEntities(lines: string[]) {
  return lines.flatMap((line) => {
    const m = line.match(ENTITY_LINE);
    if (m && !line.includes('`') && !line.includes(':')) {
      return [`## ${m[1]}`, `*${m[2].trim()}*`];
    }
    return [line];
  });
}

/** Flow files: consecutive `N. ` runs → <Steps>/<Step> (sub-bullets join their step). */
function wrapSteps(lines: string[]) {
  const out: string[] = [];
  let i = 0;
  const isItem = (l: string) => /^\d+\. /.test(l);
  const isSub = (l: string) => /^ +\S/.test(l);

  while (i < lines.length) {
    if (!isItem(lines[i])) {
      out.push(lines[i]);
      i += 1;
      continue;
    }
    const steps: string[][] = [];
    while (i < lines.length && (isItem(lines[i]) || (steps.length > 0 && isSub(lines[i])))) {
      if (isItem(lines[i])) steps.push([lines[i]]);
      else steps[steps.length - 1].push(lines[i]);
      i += 1;
    }
    out.push('<Steps>');
    for (const step of steps) {
      out.push('<Step>');
      out.push(step[0].replace(/^\d+\. /, ''));
      for (const sub of step.slice(1)) out.push(sub.replace(/^ {1,4}/, '  '));
      out.push('</Step>');
    }
    out.push('</Steps>');
  }
  return out;
}

// ─────────────────────────────── page emission ─────────────────────────

function emit(relPath: string, frontmatter: Record<string, string>, body: string[]) {
  const fm = Object.entries(frontmatter)
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => `${k}: ${v}`)
    .join('\n');
  pages.push({ path: relPath, body: `---\n${fm}\n---\n\n${body.join('\n').trim()}\n` });
}

function emitMeta(relPath: string, meta: Record<string, unknown>) {
  pages.push({ path: relPath, body: `${JSON.stringify(meta, null, 2)}\n` });
}

function card(href: string, title: string, description: string, icon?: string) {
  const iconAttr = icon ? ` icon="${icon}"` : '';
  return `<Card title="${title}" description="${description}"${iconAttr} href="${href}" />`;
}

const DOC_URL = (p: string) => `/en/docs/${p}`;

// ─────────────────────────────── BRD ───────────────────────────────────

{
  const lines = read(SRC.brd);
  const s = splitH2(lines);
  const sec = (n: number) => s.find((x) => x.title.startsWith(`${n}. `))!;

  emit(
    'brd/index.mdx',
    { title: 'Business Requirements', description: 'Context, scope, stakeholders and commercial model of the Trips Enjoy platform.', icon: 'Briefcase' },
    [
      ...withHeading(sec(1).title, docControlToCallout(sec(1).body)),
      ...withHeading(sec(2).title, sec(2).body),
      ...withHeading(sec(3).title, sec(3).body),
      ...withHeading(sec(4).title, sec(4).body),
      ...withHeading(sec(5).title, sec(5).body),
    ],
  );

  emit(
    'brd/requirements.mdx',
    { title: 'Business Requirements & Rules', description: 'BR-requirements by module and the platform business rules (BRL).', icon: 'ListChecks' },
    [...withHeading(sec(6).title, sec(6).body), ...withHeading(sec(7).title, sec(7).body)],
  );

  emit(
    'brd/compliance.mdx',
    { title: 'Compliance, Risks & Decisions', description: 'Compliance and safety, assumptions, risks, open decisions and approval.', icon: 'ShieldCheck' },
    [
      ...withHeading(sec(8).title, sec(8).body),
      ...withHeading(sec(9).title, sec(9).body),
      ...withHeading(sec(10).title, sec(10).body),
      ...withHeading(sec(11).title, sec(11).body),
      ...withHeading(sec(12).title, sec(12).body),
    ],
  );

  emitMeta('brd/meta.json', {
    title: 'Business (BRD)',
    icon: 'Briefcase',
    pages: ['index', 'requirements', 'compliance'],
  });
}

// ─────────────────────────────── SRS ───────────────────────────────────

const modulePages: { code: string; title: string; path: string }[] = [];

{
  const lines = read(SRC.srs);
  const s = splitH2(lines);
  const sec = (n: number) => s.find((x) => x.title.startsWith(`${n}. `))!;

  emit(
    'srs/index.mdx',
    { title: 'Software Requirements', description: 'Introduction, overall description and external interfaces of the SRS.', icon: 'ClipboardList' },
    [
      ...withHeading(sec(1).title, docControlToCallout(sec(1).body)),
      ...withHeading(sec(2).title, sec(2).body),
      ...withHeading(sec(3).title, sec(3).body),
      ...withHeading(sec(4).title, sec(4).body),
      '## Module index',
      '',
      'Section 5 is split into one page per module:',
      '',
      '<Cards>',
      ...splitH3(sec(5).body).map((m) => {
        const mm = m.title.match(/^5\.\d+\s+(.+?)\s+\(([A-Z]+)\)$/);
        const title = mm ? mm[1] : m.title;
        const code = mm ? mm[2] : 'MOD';
        const path = `srs/requirements/${code.toLowerCase()}`;
        modulePages.push({ code, title, path });
        return `  ${card(DOC_URL(path), code, title)}`;
      }),
      '</Cards>',
      '',
      trimBody(sec(5).body.slice(0, 2)).join('\n'), // "Each module lists…" intro
    ],
  );

  // one page per module
  for (const m of splitH3(sec(5).body)) {
    const mm = m.title.match(/^5\.\d+\s+(.+?)\s+\(([A-Z]+)\)$/);
    if (!mm) continue;
    const [, title, code] = mm;
    emit(
      `srs/requirements/${code.toLowerCase()}.mdx`,
      {
        title: `${code} — ${title}`,
        description: `Functional requirements for ${title} (${code}).`,
      },
      [
        `Traces to the [business requirements](${DOC_URL('brd/requirements')}).`,
        '',
        ...dedentFences(transformFRs(trimBody(m.body))),
      ],
    );
  }

  emit(
    'srs/data.mdx',
    { title: 'Data Requirements', description: 'Core entities, isolation, soft delete and retention.', icon: 'Table2' },
    dedentFences(trimBody(sec(6).body)),
  );
  emit(
    'srs/state-machines.mdx',
    { title: 'State Machines', description: 'Trip, booking, seat and deal lifecycles as event/guard transition tables.', icon: 'GitBranch' },
    dedentFences(trimBody(sec(7).body)),
  );
  emit(
    'srs/quality.mdx',
    { title: 'Quality & Interface Requirements', description: 'Non-functional requirements and interface/API requirements.', icon: 'Gauge' },
    dedentFences([...withHeading(sec(8).title, sec(8).body), '', ...withHeading(sec(9).title, sec(9).body)]),
  );
  emit(
    'srs/traceability.mdx',
    { title: 'Traceability & Open Items', description: 'BRD→SRS traceability matrix, open items and conflict resolutions.', icon: 'Link2' },
    dedentFences([...withHeading(sec(10).title, sec(10).body), '', ...withHeading(sec(11).title, sec(11).body)]),
  );

  emitMeta('srs/meta.json', {
    title: 'Software (SRS)',
    icon: 'ClipboardList',
    pages: ['index', 'requirements', 'data', 'state-machines', 'quality', 'traceability'],
  });
  emitMeta('srs/requirements/meta.json', {
    title: 'Requirements',
    defaultOpen: false,
    pages: modulePages.map((m) => m.path.split('/').pop()),
  });
}

// ─────────────────────────────── ERD ───────────────────────────────────

{
  const lines = read(SRC.erd);
  const s = splitH2(lines);
  const sec = (n: number) => s.find((x) => x.title.startsWith(`${n}. `))!;
  const domainSec = s.find((x) => x.title.startsWith('3. '))!;
  const domains = splitH3(domainSec.body);

  const domainInfo = domains.map((d) => {
    const dm = d.title.match(/^3\.\d+\s+(.+)$/)!;
    const entities = (d.body.join('\n').match(/^\*\*[a-z_][a-z0-9_]*\*\*/gm) ?? []).length;
    return { title: dm[1], path: `erd/domains/${slug(dm[1])}`, entities };
  });

  emit(
    'erd/index.mdx',
    { title: 'Data Model', description: 'Conventions, domain map and entry points into the entity-relationship design.', icon: 'Database' },
    [
      ...docControlToCallout(trimBody(headChunk(lines))),
      ...dedentFences(trimBody(sec(1).body)),
      ...dedentFences(trimBody(sec(2).body)),
      '## Domains',
      '',
      '<Cards>',
      ...domainInfo.map((d) => `  ${card(DOC_URL(d.path), d.title, `${d.entities} entities`)}`),
      '</Cards>',
    ],
  );

  for (let i = 0; i < domains.length; i++) {
    const d = domains[i];
    const dm = d.title.match(/^3\.\d+\s+(.+)$/)!;
    emit(
      `erd/domains/${slug(dm[1])}.mdx`,
      {
        title: dm[1],
        description: `Tables and relationships for the ${dm[1].toLowerCase()} domain.`,
      },
      [
        `Part of the [data model](${DOC_URL('erd')}); conventions and base columns are documented there.`,
        '',
        ...transformEntities(dedentFences(trimBody(d.body))),
      ],
    );
  }

  emit(
    'erd/reference.mdx',
    { title: 'Lookup Catalog & Access Paths', description: 'Lookup type catalog, row-level security paths and SRS traceability.', icon: 'BookOpen' },
    dedentFences([
      ...withHeading(sec(4).title, sec(4).body),
      '',
      ...withHeading(sec(5).title, sec(5).body),
      '',
      ...withHeading(sec(6).title, sec(6).body),
    ]),
  );

  emitMeta('erd/meta.json', {
    title: 'Data Model (ERD)',
    icon: 'Database',
    pages: ['index', 'domains', 'reference'],
  });
  emitMeta('erd/domains/meta.json', {
    title: 'Domains',
    defaultOpen: false,
    pages: domainInfo.map((d) => d.path.split('/').pop()),
  });
}

// ─────────────────────────────── user flows ────────────────────────────

{
  const files: [string, string, string][] = [
    ['01-traveler', 'traveler', 'Traveler App'],
    ['02-organization', 'organization', 'Organization Dashboard'],
    ['03-admin', 'admin', 'Admin Console'],
    ['04-driver', 'driver', 'Driver App'],
    ['05-photographer', 'photographer', 'Photographer Studio'],
    ['06-moderator', 'moderator', 'Moderator App'],
    ['07-parent', 'parent', 'Parent Tracker'],
    ['08-shared', 'shared', 'Shared Flows'],
  ];

  // index page from 00-README
  const idx = read(SRC.flows('00-README'));
  const s = splitH2(idx);
  const body: string[] = [...docControlToCallout(trimBody(headChunk(idx)))];
  for (const sec of s) {
    if (sec.title === 'Index') {
      // the plain index table is replaced by Cards
      body.push('## Flows by role', '', '<Cards>');
      for (const [file, slugName, title] of files) {
        const flowCount = (read(SRC.flows(file)).join('\n').match(/^### UF-/gm) ?? []).length;
        body.push(`  ${card(DOC_URL(`user-flows/${slugName}`), title, `${flowCount} flows`)}`);
      }
      body.push('</Cards>');
      continue;
    }
    body.push(...dedentFences(trimBody(sec.body)));
  }
  emit(
    'user-flows/index.mdx',
    {
      title: 'User Flows',
      description: 'How every role registers, gets approved and uses the platform, with rule traces.',
      icon: 'Route',
    },
    body,
  );

  for (const [file, slugName, title] of files) {
    const lines = read(SRC.flows(file));
    const dropH1 = lines.slice(1); // `# NN. Title` → frontmatter
    const linked = dropH1.map((l) =>
      l.replace(/\b(0[1-8]-[a-z-]+)\.md\b/g, (_all, name) => {
        const target = files.find(([f]) => f === name);
        return target ? `[${
          files.find(([f]) => f === name)![2]
        }](${DOC_URL(`user-flows/${target[1]}`)})` : name;
      }),
    );
    emit(
      `user-flows/${slugName}.mdx`,
      {
        title,
        description: `End-to-end flows for the ${title.toLowerCase()} (UF IDs, steps, rules).`,
        icon: 'PersonStanding',
      },
      dedentFences(wrapSteps(trimBody(linked))),
    );
  }

  emitMeta('user-flows/meta.json', {
    title: 'User Flows',
    icon: 'Route',
    pages: ['index', ...files.map(([, s]) => s)],
  });
}

// ─────────────────────────────── for AI agents ─────────────────────────

const AI_PAGE = [
  'This documentation set is designed to be consumed by AI agents as well as people: every page is reachable as clean Markdown, full-text searchable, and queryable through an MCP server.',
  '',
  '## Endpoints',
  '',
  '| Endpoint | Returns |',
  '|---|---|',
  '| `/llms.txt` | Curated index of the whole set, grouped by section |',
  '| `/llms-full.txt` | The complete documentation as one Markdown document |',
  '| `/en/docs/<path>.md` | Any single page as processed Markdown, e.g. `/en/docs/srs/requirements/auth.md` |',
  '| `/api/search?query=…` | Full-text search with ranked hits and section anchors |',
  '| `/api/mcp` | MCP server (Streamable HTTP, JSON-RPC 2.0) |',
  '',
  '## MCP server',
  '',
  'Connect from any MCP client (Claude Desktop, Cursor, …) to `https://<your-host>/api/mcp`. The server is stateless — no keys, no session.',
  '',
  '| Tool | Input | Returns |',
  '|---|---|---|',
  '| `list_docs` | — | All pages with titles, URLs and descriptions |',
  '| `search_docs` | `query` | Ranked hits with section anchors (same index as the site search) |',
  '| `get_doc` | `path` | The page as full processed Markdown |',
  '',
  'Example call:',
  '',
  '```bash',
  "curl -X POST https://<your-host>/api/mcp \\",
  "  -H 'Content-Type: application/json' \\",
  "  -H 'Accept: application/json, text/event-stream' \\",
  "  -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_docs\",\"arguments\":{\"query\":\"seat hold\"}}}'",
  '```',
  '',
  '## Requirement ID vocabulary',
  '',
  '| Prefix | Meaning | Defined in |',
  '|---|---|---|',
  '| `FR-` | Functional requirement | Software (SRS) module pages |',
  '| `BR-` | Business requirement | Business Requirements and Rules |',
  '| `BRL-` | Business rule | Business Requirements and Rules |',
  '| `NFR-` | Non-functional requirement | Quality and Interface Requirements |',
  '| `DAT-` | Data requirement | Data Requirements |',
  '| `OD-` | Open decision | Compliance, Risks and Decisions |',
  '| `UF-` | User flow | User Flows |',
  '| `D-` | Decision register entry — an external document, not part of this site | — |',
];

emit(
  'ai.mdx',
  {
    title: 'For AI Agents',
    description: 'Machine-readable access: llms.txt, per-page Markdown, search API and an MCP server.',
    icon: 'Bot',
  },
  AI_PAGE,
);

// ─────────────────────────────── landing ───────────────────────────────

emit(
  'index.mdx',
  {
    title: 'Trips Enjoy Documentation',
    description: 'Business requirements, software requirements, data model and user flows for the Trips Enjoy platform.',
    icon: 'Rocket',
  },
  [
    'Trips Enjoy is a multi-role trips marketplace: travelers book seats on organized trips, organizations publish and operate them, and drivers, photographers and moderators deliver the experience. This is the complete product documentation set.',
    '',
    'All pages exist in Arabic and English interfaces; this documentation is written in English.',
    '',
    '<Cards>',
    `  ${card(DOC_URL('brd'), 'Business (BRD)', 'Objectives, scope, stakeholders, commercial model, business rules, risks and open decisions.', 'Briefcase')}`,
    `  ${card(DOC_URL('srs'), 'Software (SRS)', '201 functional requirements across 28 modules, data requirements, state machines and NFRs.', 'ClipboardList')}`,
    `  ${card(DOC_URL('erd'), 'Data Model (ERD)', '14 domains of tables and relationships, lookup catalog and row-level security paths.', 'Database')}`,
    `  ${card(DOC_URL('user-flows'), 'User Flows', '95 role flows: registration, approval, booking, operations and shared behaviors.', 'Route')}`,
    `  ${card(DOC_URL('ai'), 'For AI Agents', 'llms.txt, per-page Markdown, search API and an MCP server for AI clients.', 'Bot')}`,
    '</Cards>',
    '',
    '## How to read the set',
    '',
    '- Requirements cite each other by stable ID: `BR-*` (business), `BRL-*` (rules), `FR-*` (functional), `D-*` (decisions), `OD-*` (open decisions) and `UF-*` (flows).',
    '- Every SRS requirement page lists its MoSCoW priority and the upstream BRD IDs it traces to.',
    '- Items marked `[OPEN]` or *(placeholder)* follow the open-decision process in the BRD.',
  ],
);

emitMeta('meta.json', { pages: ['index', 'brd', 'srs', 'erd', 'user-flows', 'ai'] });

// ─────────────────────────────── write out ─────────────────────────────

rmSync(OUT, { recursive: true, force: true });
for (const p of pages) {
  if (p.path.endsWith('.json')) {
    const full = join(OUT, p.path);
    mkdirSync(resolve(full, '..'), { recursive: true });
    writeFileSync(full, p.body);
  } else {
    const full = join(OUT, p.path);
    mkdirSync(resolve(full, '..'), { recursive: true });
    writeFileSync(full, p.body);
  }
}
console.log(`wrote ${pages.length} files under content/docs`);

// ─────────────────────────────── fidelity report ───────────────────────

const sourceCorpus = [SRC.brd, SRC.srs, SRC.erd, ...['00-README', '01-traveler', '02-organization', '03-admin', '04-driver', '05-photographer', '06-moderator', '07-parent', '08-shared'].map(SRC.flows)]
  .map(read)
  .flat()
  .join('\n');
const outputCorpus = pages.map((p) => p.body).join('\n');

const count = (s: string, re: RegExp) => (s.match(new RegExp(re.source, 'gm')) ?? []).length;

type Check = { name: string; re: RegExp; expect?: number; note?: string };
const checks: Check[] = [
  { name: 'FR headings', re: /\*\*FR-[A-Z]+-\d+\./, note: 'in: bold run-ins · out: ## headings' },
  { name: 'ReqMeta', re: /<ReqMeta priority=/, note: 'out must equal FR headings' },
  { name: 'UF headings', re: /^### UF-/ },
  { name: 'mermaid fences', re: /^```mermaid/ },
  { name: '[OPEN] markers', re: /\[OPEN[:\]]/ },
  { name: 'Given/when/then', re: /^- Given / },
  { name: 'table separators (−4 doc-control)', re: /^\|---/ },
  { name: 'Step components', re: /<Step>/, note: 'out: wrapped flow steps' },
];

let failed = false;
console.log('\nfidelity report');
for (const c of checks) {
  const inCount = count(sourceCorpus, c.re);
  const outCount = count(outputCorpus, c.re);
  const label = c.name === 'ReqMeta' || c.name === 'Step components' ? '   out' : 'in/out';
  console.log(`  ${label}: ${c.name.padEnd(38)} ${String(inCount).padStart(4)} → ${String(outCount).padStart(4)}  ${c.note ?? ''}`);
}

// strict checks
const frIn = count(sourceCorpus, /\*\*FR-[A-Z]+-\d+\./);
const frOut = count(outputCorpus, /^## FR-[A-Z]+-\d+ —/);
const metaOut = count(outputCorpus, /<ReqMeta priority=/);
const ufIn = count(sourceCorpus, /^### UF-/);
const ufOut = count(outputCorpus, /^### UF-/);
const mmIn = count(sourceCorpus, /^```mermaid/);
const mmOut = count(outputCorpus, /^```mermaid/);
const openIn = count(sourceCorpus, /\[OPEN[:\]]/);
const openOut = count(outputCorpus, /\[OPEN[:\]]/);
const givenIn = count(sourceCorpus, /^- Given /);
const givenOut = count(outputCorpus, /^- Given /);
const tableIn = count(sourceCorpus, /^\|---/);
const tableOut = count(outputCorpus, /^\|---/);

const expect = (name: string, got: number, want: number) => {
  if (got !== want) {
    console.error(`  ✗ ${name}: expected ${want}, got ${got}`);
    failed = true;
  } else {
    console.log(`  ✓ ${name} = ${got}`);
  }
};

console.log('\nstrict checks');
expect('FR headings in→out', frOut, frIn);
expect('ReqMeta = FR count', metaOut, frIn);
expect('UF headings in→out', ufOut, ufIn);
expect('mermaid fences in→out', mmOut, mmIn);
expect('Given bullets in→out', givenOut, givenIn);
// 4 doc-control tables become Callouts and the 00-README index table becomes
// Cards; the hand-written AI page contributes its own tables; the landing
// page documents the [OPEN] marker once, hence +1
const aiTables = (AI_PAGE.join('\n').match(/^\|---/gm) ?? []).length;
expect('tables out = in − 5 + aiTables', tableOut, tableIn - 5 + aiTables);
expect('[OPEN] out = in + 1 (landing mention)', openOut, openIn + 1);

if (failed) {
  console.error('\nFIDELITY CHECK FAILED — do not archive sources.');
  process.exit(1);
}
console.log('\nall fidelity checks passed.');
