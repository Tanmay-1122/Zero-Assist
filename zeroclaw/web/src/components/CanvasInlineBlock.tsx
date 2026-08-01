import { useMemo, useState } from 'react';
import { Maximize2, Minimize2 } from 'lucide-react';

interface CanvasInlineBlockProps {
  canvasId: string;
  contentType: string;
  content: string;
}

export default function CanvasInlineBlock({ canvasId, contentType, content }: CanvasInlineBlockProps) {
  const [expanded, setExpanded] = useState(false);

  const srcdoc = useMemo(() => {
    const cs = getComputedStyle(document.documentElement);
    const bgBase = cs.getPropertyValue('--pc-bg-base').trim() || '#1e1e24';
    const textPrimary = cs.getPropertyValue('--pc-text-primary').trim() || '#d4d4d8';
    const textSecondary = cs.getPropertyValue('--pc-text-secondary').trim() || '#a1a1aa';
    const fontMono = cs.getPropertyValue('--pc-font-mono').trim() || 'monospace';
    const fontUi = cs.getPropertyValue('--pc-font-ui').trim() || 'system-ui,sans-serif';

    const noScriptCsp =
      '<meta http-equiv="Content-Security-Policy" content="script-src \'none\'; object-src \'none\'">';

    const inertDoc =
      `<!DOCTYPE html><html><head>${noScriptCsp}</head><body style="margin:0;background:${bgBase};"></body></html>`;

    if (contentType === 'eval') return inertDoc;

    if (contentType === 'svg') {
      const sanitized = content
        .replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, '')
        .replace(/<script\b[^>]*\/?>/gi, '')
        .replace(/\bon\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]*)/gi, '');
      return `<!DOCTYPE html><html><head>${noScriptCsp}<style>body{margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;background:${bgBase};}</style></head><body>${sanitized}</body></html>`;
    }

    if (contentType === 'markdown') {
      const escaped = content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return `<!DOCTYPE html><html><head>${noScriptCsp}<style>body{margin:1rem;font-family:${fontUi};color:${textSecondary};background:${bgBase};line-height:1.6;}pre{white-space:pre-wrap;word-wrap:break-word;}</style></head><body><pre>${escaped}</pre></body></html>`;
    }

    if (contentType === 'text') {
      const escaped = content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return `<!DOCTYPE html><html><head>${noScriptCsp}<style>body{margin:1rem;font-family:${fontMono};color:${textPrimary};background:${bgBase};white-space:pre-wrap;}</style></head><body>${escaped}</body></html>`;
    }

    if (contentType === 'html') return content;

    return inertDoc;
  }, [contentType, content]);

  const height = expanded ? '400px' : '200px';

  return (
    <div className="rounded-[var(--radius-md)] border border-pc-border overflow-hidden bg-pc-base">
      <div className="flex items-center justify-between px-3 py-1.5 bg-pc-elevated border-b border-pc-border">
        <div className="flex items-center gap-2 text-xs">
          <span className="font-mono text-pc-accent">{canvasId}</span>
          <span className="text-pc-text-muted">·</span>
          <span className="text-pc-text-muted">{contentType}</span>
        </div>
        <button
          onClick={() => setExpanded(!expanded)}
          className="p-1 rounded hover:bg-pc-hover text-pc-text-muted hover:text-pc-text transition-colors"
          title={expanded ? 'Collapse' : 'Expand'}
        >
          {expanded ? <Minimize2 className="h-3.5 w-3.5" /> : <Maximize2 className="h-3.5 w-3.5" />}
        </button>
      </div>
      <iframe
        sandbox="allow-scripts"
        srcDoc={srcdoc}
        className="w-full border-0"
        style={{ height, background: 'var(--pc-bg-base)' }}
        title={`Canvas: ${canvasId}`}
      />
    </div>
  );
}
