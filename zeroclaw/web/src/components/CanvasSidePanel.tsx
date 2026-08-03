import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { usePolling } from '@/hooks/usePolling';
import { Monitor, X, History, RefreshCw } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { basePath } from '@/lib/basePath';
import { getToken } from '@/lib/auth';
import { t } from '@/lib/i18n';

interface CanvasFrame {
  frame_id: string;
  content_type: string;
  content: string;
  timestamp: string;
}

interface WsCanvasMessage {
  type: string;
  canvas_id: string;
  frame?: CanvasFrame;
}

export interface CanvasSidePanelProps {
  open: boolean;
  onClose: () => void;
  canvasId: string;
  onCanvasIdChange: (id: string) => void;
}

export default function CanvasSidePanel({
  open,
  onClose,
  canvasId,
  onCanvasIdChange,
}: CanvasSidePanelProps) {
  const [canvasIdInput, setCanvasIdInput] = useState(canvasId);
  const [currentFrame, setCurrentFrame] = useState<CanvasFrame | null>(null);
  const [history, setHistory] = useState<CanvasFrame[]>([]);
  const [connected, setConnected] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [canvasList, setCanvasList] = useState<string[]>([]);
  const wsRef = useRef<WebSocket | null>(null);

  // Sync input when external canvasId changes
  useEffect(() => {
    setCanvasIdInput(canvasId);
  }, [canvasId]);

  const getWsUrl = useCallback((id: string) => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const base = basePath || '';
    return `${proto}//${location.host}${base}/ws/canvas/${encodeURIComponent(id)}`;
  }, []);

  const connectWs = useCallback((id: string) => {
    wsRef.current?.close();
    const token = getToken();
    const protocols = token ? ['zeroclaw.v1', `bearer.${token}`] : ['zeroclaw.v1'];
    const ws = new WebSocket(getWsUrl(id), protocols);

    ws.onopen = () => setConnected(true);
    ws.onclose = () => setConnected(false);
    ws.onerror = () => setConnected(false);

    ws.onmessage = (event) => {
      try {
        const msg: WsCanvasMessage = JSON.parse(event.data);
        if (msg.type === 'frame' && msg.frame) {
          if (msg.frame.content_type === 'clear') {
            setCurrentFrame(null);
            setHistory([]);
          } else {
            setCurrentFrame(msg.frame);
            setHistory((prev) => [...prev.slice(-49), msg.frame!]);
          }
        }
      } catch { /* ignore */ }
    };

    wsRef.current = ws;
  }, [getWsUrl]);

  useEffect(() => {
    if (!open) return;
    connectWs(canvasId);
    return () => { wsRef.current?.close(); };
  }, [open, canvasId, connectWs]);

  // Clean up WS when panel closes
  useEffect(() => {
    if (!open) {
      wsRef.current?.close();
      setConnected(false);
      setCurrentFrame(null);
      setHistory([]);
    }
  }, [open]);

  usePolling(async (isStale) => {
    try {
      const data = await apiFetch<{ canvases: string[] }>('/api/canvas');
      if (!isStale()) setCanvasList(data.canvases || []);
    } catch { /* ignore */ }
  }, 5000);

  const srcdoc = useMemo(() => {
    if (!currentFrame) return undefined;

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

    if (currentFrame.content_type === 'eval') return inertDoc;

    if (currentFrame.content_type === 'svg') {
      const sanitized = currentFrame.content
        .replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, '')
        .replace(/<script\b[^>]*\/?>/gi, '')
        .replace(/\bon\w+\s*=\s*("[^"]*"|'[^']*'|[^\s>]*)/gi, '');
      return `<!DOCTYPE html><html><head>${noScriptCsp}<style>body{margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;background:${bgBase};}</style></head><body>${sanitized}</body></html>`;
    }

    if (currentFrame.content_type === 'markdown') {
      const escaped = currentFrame.content
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return `<!DOCTYPE html><html><head>${noScriptCsp}<style>body{margin:1rem;font-family:${fontUi};color:${textSecondary};background:${bgBase};line-height:1.6;}pre{white-space:pre-wrap;word-wrap:break-word;}</style></head><body><pre>${escaped}</pre></body></html>`;
    }

    if (currentFrame.content_type === 'text') {
      const escaped = currentFrame.content
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return `<!DOCTYPE html><html><head>${noScriptCsp}<style>body{margin:1rem;font-family:${fontMono};color:${textPrimary};background:${bgBase};white-space:pre-wrap;}</style></head><body>${escaped}</body></html>`;
    }

    if (currentFrame.content_type === 'html') return currentFrame.content;

    return inertDoc;
  }, [currentFrame]);

  const handleSwitchCanvas = useCallback(() => {
    const next = canvasIdInput.trim();
    if (!next) return;
    onCanvasIdChange(next);
    setCurrentFrame(null);
    setHistory([]);
  }, [canvasIdInput, onCanvasIdChange]);

  const handleReconnect = useCallback(() => {
    connectWs(canvasId);
  }, [canvasId, connectWs]);

  const handleSelectHistoryFrame = useCallback((frame: CanvasFrame) => {
    setCurrentFrame(frame);
  }, []);

  if (!open) return null;

  return (
    <div role="dialog" aria-modal="true" className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-pc-base/70 backdrop-blur-sm" onClick={onClose} />
      <div className="relative h-full w-full sm:w-96 flex flex-col bg-pc-base border-l border-pc-border shadow-[var(--pc-shadow-md)] animate-slide-in-right overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-pc-border">
          <div className="flex items-center gap-2 min-w-0">
            <Monitor className="h-4 w-4 text-pc-accent flex-shrink-0" />
            <span className="text-sm font-medium text-pc-text truncate">{t('canvas.title')}</span>
            <span className={`inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-full ${
              connected ? 'bg-status-success/15 text-status-success' : 'bg-status-error/15 text-status-error'
            }`}>
              {connected ? t('canvas.connected') : t('canvas.disconnected')}
            </span>
          </div>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setShowHistory((v) => !v)}
              aria-pressed={showHistory}
              title={t('canvas.toggle_history')}
              className="h-7 w-7 flex items-center justify-center rounded-[var(--radius-md)] text-pc-text-muted hover:bg-[var(--pc-hover)] hover:text-pc-text transition-colors"
            >
              <History className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={handleReconnect}
              title={t('canvas.reconnect')}
              className="h-7 w-7 flex items-center justify-center rounded-[var(--radius-md)] text-pc-text-muted hover:bg-[var(--pc-hover)] hover:text-pc-text transition-colors"
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label={t('canvas.close_panel')}
              className="h-7 w-7 flex items-center justify-center rounded-[var(--radius-md)] text-pc-text-muted hover:bg-[var(--pc-hover)] hover:text-pc-text transition-colors"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>

        {/* Canvas selector */}
        <div className="flex items-center gap-1.5 px-3 py-2 border-b border-pc-border">
          <input
            type="text"
            value={canvasIdInput}
            onChange={(e) => setCanvasIdInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSwitchCanvas()}
            placeholder={t('canvas.canvas_id_placeholder')}
            className="flex-1 h-7 px-2 rounded-[var(--radius-md)] text-xs border border-pc-border bg-pc-input text-pc-text placeholder:text-pc-text-faint focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-pc-accent/40"
          />
          <button
            type="button"
            onClick={handleSwitchCanvas}
            className="h-7 px-2 rounded-[var(--radius-md)] text-xs font-medium bg-pc-elevated border border-pc-border text-pc-text-secondary hover:text-pc-text hover:border-pc-border-strong transition-colors"
          >
            {t('canvas.switch')}
          </button>
        </div>

        {/* Active canvas chips */}
        {canvasList.length > 0 && (
          <div className="flex items-center gap-1 px-3 py-1.5 border-b border-pc-border overflow-x-auto">
            {canvasList.map((id) => {
              const active = id === canvasId;
              return (
                <button
                  key={id}
                  onClick={() => {
                    setCanvasIdInput(id);
                    onCanvasIdChange(id);
                    setCurrentFrame(null);
                    setHistory([]);
                  }}
                  className={`px-2 py-0.5 rounded-full text-[10px] font-mono border transition-colors whitespace-nowrap ${
                    active
                      ? 'bg-pc-accent/10 text-pc-accent border-pc-accent/30'
                      : 'bg-pc-elevated text-pc-text-muted border-pc-border hover:text-pc-text hover:border-pc-border-strong'
                  }`}
                >
                  {id}
                </button>
              );
            })}
          </div>
        )}

        {/* Content area */}
        <div className="flex-1 flex min-h-0">
          {/* Canvas viewer */}
          <div className="flex-1 overflow-hidden">
            {currentFrame ? (
              <iframe
                sandbox="allow-scripts"
                srcDoc={srcdoc}
                className="w-full h-full border-0"
                title={`${t('canvas.iframe_title_prefix')}${canvasId}`}
                style={{ background: 'var(--pc-bg-base)' }}
              />
            ) : (
              <div className="flex items-center justify-center h-full p-6">
                <div className="text-center">
                  <Monitor className="h-10 w-10 mx-auto mb-2 text-pc-text-faint" />
                  <p className="text-xs text-pc-text-muted">
                    {t('canvas.waiting_prefix')} <span className="font-mono text-pc-text-secondary">"{canvasId}"</span>
                  </p>
                  <p className="text-[10px] mt-1 text-pc-text-faint">
                    {t('canvas.waiting_hint')}
                  </p>
                </div>
              </div>
            )}
          </div>

          {/* History sidebar */}
          {showHistory && (
            <div className="w-48 border-l border-pc-border overflow-y-auto flex-shrink-0">
              <div className="px-2 py-1.5 border-b border-pc-border text-[10px] font-medium uppercase tracking-wide text-pc-text-faint sticky top-0 bg-pc-surface">
                {t('canvas.frame_history')} ({history.length})
              </div>
              {history.length === 0 ? (
                <p className="p-2 text-[10px] text-pc-text-muted">{t('canvas.no_frames')}</p>
              ) : (
                <div className="space-y-0.5 p-1">
                  {[...history].reverse().map((frame) => {
                    const active = currentFrame?.frame_id === frame.frame_id;
                    return (
                      <button
                        key={frame.frame_id}
                        onClick={() => handleSelectHistoryFrame(frame)}
                        className={`w-full text-left px-1.5 py-1 rounded-[var(--radius-sm)] text-[10px] transition-colors border ${
                          active
                            ? 'bg-pc-accent/10 border-pc-accent/30'
                            : 'border-transparent hover:bg-[var(--pc-hover)]'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-mono truncate text-pc-accent">{frame.content_type}</span>
                          <span className="text-pc-text-muted">{new Date(frame.timestamp).toLocaleTimeString()}</span>
                        </div>
                        <div className="truncate text-pc-text-muted">
                          {frame.content.substring(0, 40)}{frame.content.length > 40 ? '...' : ''}
                        </div>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>

        {/* Frame info bar */}
        {currentFrame && (
          <div className="flex items-center justify-between px-3 py-1.5 border-t border-pc-border text-[10px] bg-pc-elevated text-pc-text-muted">
            <span>
              <span className="font-mono text-pc-text-secondary">{currentFrame.content_type}</span>
              {' · '}
              <span className="font-mono text-pc-text-secondary">{currentFrame.frame_id.substring(0, 8)}</span>
            </span>
            <span>{new Date(currentFrame.timestamp).toLocaleTimeString()}</span>
          </div>
        )}
      </div>
    </div>
  );
}
