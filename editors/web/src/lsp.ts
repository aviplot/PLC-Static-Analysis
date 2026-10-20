// A small JSON-RPC 2.0 client for the language server over WebSocket: one message per WebSocket text frame.

type Handler = (params: any) => void;

export class LspError extends Error {
  constructor(
    message: string,
    readonly code: number,
  ) {
    super(message);
  }
}

export class LspClient {
  private socket: WebSocket | null = null;
  private nextId = 1;
  private readonly pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>();
  private readonly handlers = new Map<string, Handler[]>();
  private readonly closeHandlers: ((clean: boolean) => void)[] = [];

  constructor(private readonly url: string) {}

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.socket = ws;
      ws.onopen = () => resolve();
      ws.onerror = () => reject(new Error('could not connect to the analysis server'));
      ws.onmessage = (e) => this.receive(String(e.data));
      ws.onclose = (e) => {
        for (const p of this.pending.values()) {
          p.reject(new Error('connection closed'));
        }
        this.pending.clear();
        this.closeHandlers.forEach((h) => h(e.wasClean));
      };
    });
  }

  get open(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  onNotification(method: string, handler: Handler): void {
    const list = this.handlers.get(method) ?? [];
    list.push(handler);
    this.handlers.set(method, list);
  }

  onClose(handler: (clean: boolean) => void): void {
    this.closeHandlers.push(handler);
  }

  request<T>(method: string, params: unknown): Promise<T> {
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      if (!this.open) {
        reject(new Error('not connected'));
        return;
      }
      this.pending.set(id, { resolve, reject });
      this.socket!.send(JSON.stringify({ jsonrpc: '2.0', id, method, params }));
    });
  }

  notify(method: string, params: unknown): void {
    if (this.open) {
      this.socket!.send(JSON.stringify({ jsonrpc: '2.0', method, params }));
    }
  }

  close(): void {
    const s = this.socket;
    this.socket = null;
    if (s && (s.readyState === WebSocket.OPEN || s.readyState === WebSocket.CONNECTING)) {
      s.close(1000, 'closed');
    }
  }

  private receive(text: string): void {
    let m: any;
    try {
      m = JSON.parse(text);
    } catch {
      return;
    }
    if (m.id !== undefined && m.method === undefined) {
      const p = this.pending.get(m.id);
      if (p) {
        this.pending.delete(m.id);
        if (m.error) {
          p.reject(new LspError(m.error.message ?? 'request failed', m.error.code ?? 0));
        } else {
          p.resolve(m.result);
        }
      }
    } else if (m.method !== undefined && m.id !== undefined) {
      // a request from the server (showMessageRequest, configuration, …): we answer every one with null
      this.socket?.send(JSON.stringify({ jsonrpc: '2.0', id: m.id, result: null }));
      (this.handlers.get(m.method) ?? []).forEach((h) => h(m.params));
    } else if (m.method !== undefined) {
      (this.handlers.get(m.method) ?? []).forEach((h) => h(m.params));
    }
  }
}

/** The WebSocket URL of a project's language server, on this page's own origin. */
export function lspUrl(projectId: string): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}/lsp/${encodeURIComponent(projectId)}`;
}

/** What we ask the server for: the features the workbench uses. */
export const CLIENT_CAPABILITIES = {
  textDocument: {
    synchronization: { didSave: false, dynamicRegistration: false },
    hover: { contentFormat: ['markdown', 'plaintext'] },
    definition: { linkSupport: false },
    references: {},
    documentSymbol: { hierarchicalDocumentSymbolSupport: true },
    publishDiagnostics: { relatedInformation: true, versionSupport: true },
  },
  workspace: { workspaceFolders: true, symbol: {} },
  window: { showMessage: {}, workDoneProgress: true },
};
