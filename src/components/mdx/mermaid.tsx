'use client';

import { Suspense, use, useId, useSyncExternalStore } from 'react';

/**
 * Official fumadocs Mermaid pattern (fumadocs.dev/docs/tanstack-start/markdown/mermaid),
 * hardened for this docs system:
 * - parse errors fall back to the diagram source instead of crashing (the diagrams are
 *   load-bearing documentation; a broken one must not take the page down),
 * - every render branch carries the `.mermaid-diagram` hook class the QA sweeps select on,
 * - React's `useId()` is sanitized to plain alphanumerics (mermaid embeds it in SVG ids
 *   and CSS selectors, where ':' breaks),
 * - a Suspense skeleton covers the client-suspense window so diagrams don't pop in blank.
 *
 * Produced by `remarkMdxMermaid` from ```mermaid fences — the plugin emits
 * `<Mermaid chart="..." />` with the diagram source as a plain string attribute.
 */

type MermaidModule = typeof import('mermaid');

type RenderResult = { svg?: string; error?: string; bindFunctions?: (element: Element) => void };

const cache = new Map<string, Promise<unknown>>();

function cachePromise<T>(key: string, setPromise: () => Promise<T>): Promise<T> {
  const cached = cache.get(key);
  if (cached) return cached as Promise<T>;

  const promise = setPromise();
  cache.set(key, promise);
  return promise;
}

function subscribeTheme(onChange: () => void) {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, { attributeFilter: ['class'] });
  return () => observer.disconnect();
}

// mermaid v12's MermaidConfig['theme'] resolves to `string` through the
// initialize overloads, so the literal union is declared here instead.
type MermaidTheme = 'base' | 'dark' | 'default' | 'forest' | 'neutral' | 'null';

function getTheme(): MermaidTheme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'default';
}

function MermaidBody({ chart, id }: { chart: string; id: string }) {
  const theme = useSyncExternalStore<MermaidTheme>(subscribeTheme, getTheme, () => 'default');
  const { default: mermaid } = use(cachePromise<MermaidModule>('mermaid', () => import('mermaid')));
  const { svg, error, bindFunctions } = use(
    cachePromise<RenderResult>(`render:${chart}:${theme}`, async () => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'loose',
        fontFamily: 'inherit',
        themeCSS: 'margin: 1.5rem auto 0;',
        theme,
      });
      try {
        const { svg, bindFunctions } = await mermaid.render(id, chart);
        return { svg, bindFunctions };
      } catch (cause) {
        return { error: String(cause) };
      }
    }),
  );

  if (error) {
    return (
      <div className="mermaid-diagram my-4">
        <p className="mb-2 text-sm font-medium text-red-11">
          Diagram failed to parse — showing source instead.
        </p>
        <pre className="overflow-x-auto rounded-lg border border-red-6 bg-red-2/40 p-4 text-sm">
          <code>{chart}</code>
        </pre>
      </div>
    );
  }

  return (
    <div
      className="mermaid-diagram overflow-x-auto [&_svg]:mx-auto [&_svg]:max-w-full"
      ref={(container) => {
        if (container) bindFunctions?.(container);
      }}
      dangerouslySetInnerHTML={{ __html: svg ?? '' }}
    />
  );
}

export function Mermaid({ chart }: { chart: string }) {
  // `false` on the server and during hydration — hydration-safe gate
  const isClient = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  );

  // mermaid ids must be plain alphanumerics; React's useId contains ':'
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');

  if (!isClient) return null;

  return (
    <Suspense
      fallback={<div aria-hidden className="min-h-24 animate-pulse rounded-lg bg-fc-muted" />}
    >
      <MermaidBody chart={chart} id={`mermaid-${id}`} />
    </Suspense>
  );
}
