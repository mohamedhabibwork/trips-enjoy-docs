import { useEffect, useId, useState } from 'react';

function isDarkMode(): boolean {
  return typeof document !== 'undefined' && document.documentElement.classList.contains('dark');
}

/**
 * Renders a mermaid diagram client-side.
 *
 * Produced by `remarkMdxMermaid` from ```mermaid fences — the plugin emits
 * `<Mermaid chart="..." />` with the diagram source as a plain string
 * attribute, so braces in erDiagram syntax (`||--o{`) stay intact.
 *
 * Rendering happens in an effect (mermaid needs a DOM), re-runs when the
 * color scheme flips, and falls back to the raw source on a parse error.
 */
export function Mermaid({ chart }: { chart: string }) {
  // mermaid ids must be plain alphanumerics; React's useId contains ':'
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');
  const [svg, setSvg] = useState<string>();
  const [error, setError] = useState<string>();
  // `dark` never affects the first paint (svg is still undefined then), so
  // lazy init is hydration-safe
  const [dark, setDark] = useState(() => isDarkMode());

  useEffect(() => {
    const root = document.documentElement;
    const observer = new MutationObserver(() => setDark(isDarkMode()));
    observer.observe(root, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    let disposed = false;
    (async () => {
      try {
        const mermaid = (await import('mermaid')).default;
        mermaid.initialize({
          startOnLoad: false,
          theme: dark ? 'dark' : 'default',
          fontFamily: 'inherit',
        });
        const { svg } = await mermaid.render(`mermaid-${id}-${dark ? 'd' : 'l'}`, chart);
        if (!disposed) {
          setSvg(svg);
          setError(undefined);
        }
      } catch (cause) {
        if (!disposed) setError(String(cause));
      }
    })();
    return () => {
      disposed = true;
    };
  }, [chart, id, dark]);

  if (error) {
    return (
      <div className="mermaid-diagram my-4">
        <p className="mb-2 text-sm font-medium text-red-11">
          Diagram failed to parse — showing source instead.
        </p>
        <pre className="border border-red-6 bg-red-2/40 rounded-lg p-4 text-sm overflow-x-auto">
          <code>{chart}</code>
        </pre>
      </div>
    );
  }

  if (!svg) {
    // skeleton while the diagram renders on the client
    return <div aria-hidden className="min-h-24 animate-pulse rounded-lg bg-fc-muted" />;
  }

  // eslint-disable-next-line react-dom/no-dangerously-set-innerhtml -- mermaid returns sanitized SVG
  return <div className="mermaid-diagram overflow-x-auto [&_svg]:mx-auto" dangerouslySetInnerHTML={{ __html: svg }} />;
}
