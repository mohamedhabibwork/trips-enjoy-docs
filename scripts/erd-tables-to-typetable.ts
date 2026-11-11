#!/usr/bin/env bun
/**
 * erd-tables-to-typetable.ts — convert the ERD system-table listings in
 * erd/domains/*.mdx (EN + AR mirrors) from `| Column | Type | Notes |`
 * markdown tables into <TypeTable> components.
 *
 * Mapping per row:
 *   name   → the object key (column name, verbatim — may contain commas)
 *   type   → TypeNode.type
 *   notes  → TypeNode.description (markdown inline preserved; the component
 *            renders links/code/bold in string descriptions). Empty → omitted.
 *
 * Only tables whose header row matches exactly are converted; any other
 * markdown table in these files is left alone. Idempotent: after conversion
 * no matching header remains, so a re-run is a no-op.
 *
 * Run: bun scripts/erd-tables-to-typetable.ts
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const DIRS = [
  join(ROOT, 'content', 'docs', 'erd', 'domains'),
  join(ROOT, 'content', 'docs', 'ar', 'erd', 'domains'),
];

const HEADERS = new Set(['Column|Type|Notes', 'العمود|النوع|ملاحظات']);

function cells(line: string): string[] {
  return line.split('|').slice(1, -1).map((c) => c.trim());
}

function emitTypeTable(rows: [string, string, string][]): string {
  const entries = rows.map(([name, type, notes]) => {
    const fields = [`type: ${JSON.stringify(type)}`, 'required: true'];
    if (notes) fields.push(`description: ${JSON.stringify(notes)}`);
    return `    ${JSON.stringify(name)}: {\n      ${fields.join(',\n      ')}\n    },`;
  });
  return `<TypeTable\n  type={{\n${entries.join('\n')}\n  }}\n/>`;
}

let filesChanged = 0;
let tablesConverted = 0;

for (const dir of DIRS) {
  for (const name of readdirSync(dir)) {
    if (!name.endsWith('.mdx')) continue;
    const path = join(dir, name);
    const lines = readFileSync(path, 'utf8').split('\n');

    const out: string[] = [];
    let i = 0;
    let changed = false;

    while (i < lines.length) {
      const line = lines[i];
      const header = cells(line).join('|');
      if (!HEADERS.has(header)) {
        out.push(line);
        i += 1;
        continue;
      }

      // header + separator + rows, ending at the first non-`|` line
      const sep = lines[i + 1] ?? '';
      const body: string[] = [];
      let j = i + 2;
      while (j < lines.length && lines[j].startsWith('|')) {
        body.push(lines[j]);
        j += 1;
      }
      if (!sep.startsWith('|')) {
        // malformed — leave untouched
        out.push(line);
        i += 1;
        continue;
      }

      const rows: [string, string, string][] = [];
      let bad = false;
      for (const rowLine of body) {
        const c = cells(rowLine);
        if (c.length !== 3) {
          console.error(`  ! ${path}: row with ${c.length} cells skipped: ${rowLine.slice(0, 60)}`);
          bad = true;
          break;
        }
        rows.push([c[0], c[1], c[2]]);
      }
      if (bad) {
        out.push(line);
        i += 1;
        continue;
      }

      out.push(emitTypeTable(rows));
      // keep one blank line after the block (blank line before it is untouched)
      if (lines[j] !== '') out.push('');
      tablesConverted += 1;
      changed = true;
      i = j;
    }

    if (changed) {
      writeFileSync(path, out.join('\n'));
      filesChanged += 1;
    }
  }
}

console.log(`files changed: ${filesChanged}`);
console.log(`tables converted: ${tablesConverted}`);
