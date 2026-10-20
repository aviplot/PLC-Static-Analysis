// The workbench: open a project export, read its findings, look at its code and ladder, compare two versions.

import { useCallback, useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { type FileInfo, type ProjectInfo, type Reencoded, type SessionInfo, api } from './api.ts';
import { PLC_LANGUAGES, modelUri, monaco, registerFeatures, registerLanguages, setMarkers, themeName, toMonacoRange } from './editor.ts';
import { CLIENT_CAPABILITIES, LspClient, lspUrl } from './lsp.ts';
import {
  type Filter,
  type Finding,
  type Range,
  type RuleInfo,
  PREFIX,
  countsByPath,
  emptyFilter,
  formatBytes,
  languageOf,
  mayHaveDiagrams,
  pathOf,
  severityOf,
  uriOf,
} from './model.ts';
import { Compare } from './ui/compare.tsx';
import { Diagrams } from './ui/diagrams.tsx';
import { Findings } from './ui/findings.tsx';
import { CountLamps, Lamp } from './ui/lamps.tsx';
import { FileTree } from './ui/tree.tsx';
import { type Picked, fromDrop, fromFileList, save } from './upload.ts';

/** Files larger than this are shown read-only and analysed from the server's copy (the server's maxFileSizeMb). */
const LARGE = 20 * 1024 * 1024;

interface Open {
  model: monaco.editor.ITextModel;
  /** Alternative version id of the text as loaded: equal means unedited. */
  clean: number;
  /** LSP version; 0 until the first edit opens the document on the server. */
  version: number;
  large: boolean;
}

type Connection = 'offline' | 'connecting' | 'ready' | 'lost';

export function App() {
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [rules, setRules] = useState<Map<string, RuleInfo>>(new Map());
  const [project, setProject] = useState<ProjectInfo | null>(null);
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [findings, setFindings] = useState<Finding[]>([]);
  const [tabs, setTabs] = useState<string[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [mode, setMode] = useState<'text' | 'diagrams'>('text');
  const [view, setView] = useState<'review' | 'compare'>('review');
  const [side, setSide] = useState<'files' | 'outline'>('files');
  const [connection, setConnection] = useState<Connection>('offline');
  const [analysing, setAnalysing] = useState(false);
  const [filter, setFilter] = useState<Filter>(emptyFilter);
  const [selected, setSelected] = useState<Finding | null>(null);
  const [dirty, setDirty] = useState<Set<string>>(new Set());
  const [upload, setUpload] = useState<{ name: string; fraction: number } | null>(null);
  const [message, setMessage] = useState<{ text: string; severity: 'error' | 'warning' } | null>(null);
  const [about, setAbout] = useState<string | null>(null);

  const lsp = useRef<LspClient | null>(null);
  const opens = useRef(new Map<string, Open>());
  const diagnostics = useRef(new Map<string, Finding[]>());
  const flushTimer = useRef(0);
  const projectRef = useRef<ProjectInfo | null>(null);
  const [lspState, setLspState] = useState<LspClient | null>(null);
  const reveal = useRef<{ path: string; range: Range } | null>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);

  const fail = useCallback((m: string) => setMessage({ text: m, severity: 'error' }), []);

  // ---- start ------------------------------------------------------------------------------

  useEffect(() => {
    registerLanguages();
    registerFeatures(
      () => lsp.current,
      (path) => ensureModel(path),
      (path, range) => openFile(path, range),
    );
    Promise.all([api.session(), api.rules()])
      .then(([s, r]) => {
        setSession(s);
        setRules(new Map(r.map((x) => [x.id, x])));
        const last = sessionStorageGet('plca.project');
        const p = s.projects.find((x) => x.id === last) ?? s.projects[s.projects.length - 1];
        if (p) {
          openProject(p);
        }
      })
      .catch((e) => fail(`The workbench server did not answer: ${e.message ?? e}`));
    const dark = matchMedia('(prefers-color-scheme: dark)');
    const onTheme = () => monaco.editor.setTheme(themeName());
    dark.addEventListener('change', onTheme);
    return () => dark.removeEventListener('change', onTheme);
  }, []);

  // ---- diagnostics --------------------------------------------------------------------------

  const flush = () => {
    flushTimer.current = 0;
    const all: Finding[] = [];
    diagnostics.current.forEach((list) => all.push(...list));
    setFindings(all);
  };

  const onDiagnostics = (params: any) => {
    const path = pathOf(params.uri);
    if (path === null) {
      return;
    }
    const list: Finding[] = (params.diagnostics ?? []).map((d: any) => ({
      path,
      rule: String(d.code ?? 'plca'),
      severity: severityOf(d.severity),
      message: d.message,
      range: d.range,
    }));
    if (list.length === 0) {
      diagnostics.current.delete(path);
    } else {
      diagnostics.current.set(path, list);
    }
    const open = opens.current.get(path);
    if (open) {
      setMarkers(open.model, list);
    }
    // a big project publishes thousands of files at start: re-render a few times a second, not per file
    if (!flushTimer.current) {
      flushTimer.current = window.setTimeout(flush, 150);
    }
  };

  // ---- projects -----------------------------------------------------------------------------

  const closeCurrent = () => {
    lsp.current?.close();
    lsp.current = null;
    setLspState(null);
    opens.current.forEach((o) => o.model.dispose());
    opens.current.clear();
    diagnostics.current.clear();
    setFindings([]);
    setTabs([]);
    setActive(null);
    setDirty(new Set());
    setSelected(null);
    setFiles([]);
  };

  const openProject = async (p: ProjectInfo) => {
    closeCurrent();
    projectRef.current = p;
    setProject(p);
    setView('review');
    sessionStorageSet('plca.project', p.id);
    try {
      setFiles(await api.tree(p.id));
    } catch (e: any) {
      fail(`Could not list the project's files: ${e.message ?? e}`);
      return;
    }
    connect(p);
  };

  const connect = async (p: ProjectInfo) => {
    const client = new LspClient(lspUrl(p.id));
    setConnection('connecting');
    client.onNotification('textDocument/publishDiagnostics', onDiagnostics);
    client.onNotification('window/logMessage', (m) => {
      if (/plca: analysed /.test(m.message)) {
        setAnalysing(false);
      }
    });
    client.onNotification('window/showMessage', (m) => {
      if (m.type <= 2) {
        fail(m.message);
      }
    });
    client.onClose(() => {
      if (lsp.current === client) {
        setConnection('lost');
      }
    });
    try {
      await client.connect();
      if (projectRef.current?.id !== p.id) {
        client.close();
        return;
      }
      lsp.current = client;
      await client.request('initialize', {
        processId: null,
        clientInfo: { name: 'plca workbench' },
        rootUri: PREFIX,
        workspaceFolders: [{ uri: PREFIX, name: p.name }],
        capabilities: CLIENT_CAPABILITIES,
      });
      client.notify('initialized', {});
      setAnalysing(true);
      setConnection('ready');
      setLspState(client);
    } catch (e: any) {
      setConnection('lost');
      fail(`Could not start the analysis of ${p.name}: ${e.message ?? e}`);
    }
  };

  const closeProject = async () => {
    const p = project;
    if (!p) {
      return;
    }
    if (dirty.size > 0 && !confirm(`${dirty.size} edited file(s) will be lost. Close ${p.name}?`)) {
      return;
    }
    closeCurrent();
    setProject(null);
    projectRef.current = null;
    try {
      await api.remove(p.id);
    } catch {
      // already gone (deleted after its session ended)
    }
    const s = await api.session();
    setSession(s);
    const next = s.projects[s.projects.length - 1];
    if (next) {
      openProject(next);
    }
  };

  const doUpload = async (picked: Promise<Picked>) => {
    try {
      const p = await picked;
      setUpload({ name: p.name, fraction: 0 });
      const info = await api.upload(p.name, p.zip, (fraction) => setUpload({ name: p.name, fraction }));
      setSession(await api.session());
      openProject(info);
    } catch (e: any) {
      fail(e.message ?? String(e));
    } finally {
      setUpload(null);
    }
  };

  // ---- files and editors --------------------------------------------------------------------

  const ensureModel = async (path: string): Promise<monaco.editor.ITextModel | null> => {
    const known = opens.current.get(path);
    if (known) {
      return known.model;
    }
    const p = projectRef.current;
    if (!p) {
      return null;
    }
    const text = await api.file(p.id, path);
    const again = opens.current.get(path);
    if (again) {
      return again.model; // loaded meanwhile
    }
    const model = monaco.editor.createModel(text, languageOf(path, text.slice(0, 4096)), modelUri(path));
    const size = files.find((f) => f.path === path)?.size ?? text.length;
    const open: Open = { model, clean: model.getAlternativeVersionId(), version: 0, large: size > LARGE };
    opens.current.set(path, open);
    setMarkers(model, diagnostics.current.get(path) ?? []);
    let timer = 0;
    model.onDidChangeContent(() => {
      const isDirty = model.getAlternativeVersionId() !== open.clean;
      setDirty((d) => {
        if (d.has(path) === isDirty) {
          return d;
        }
        const n = new Set(d);
        isDirty ? n.add(path) : n.delete(path);
        return n;
      });
      window.clearTimeout(timer);
      timer = window.setTimeout(() => sendEdit(path, open), 150);
    });
    return model;
  };

  const sendEdit = (path: string, open: Open) => {
    const c = lsp.current;
    if (!c || open.large) {
      return;
    }
    const text = open.model.getValue();
    if (open.version === 0) {
      open.version = 1;
      c.notify('textDocument/didOpen', {
        textDocument: { uri: uriOf(path), languageId: open.model.getLanguageId(), version: 1, text },
      });
    } else {
      open.version++;
      c.notify('textDocument/didChange', {
        textDocument: { uri: uriOf(path), version: open.version },
        contentChanges: [{ text }],
      });
    }
    setAnalysing(true);
  };

  const openFile = async (path: string, range: Range | null) => {
    setView('review');
    setTabs((t) => (t.includes(path) ? t : [...t, path]));
    setActive(path);
    if (range) {
      reveal.current = { path, range };
      setMode('text');
    }
    try {
      await ensureModel(path);
      // the editor effect picks the model up; a reveal for the already active file is applied here
      applyReveal();
    } catch (e: any) {
      fail(`Could not open ${path}: ${e.message ?? e}`);
    }
  };

  const applyReveal = () => {
    const r = reveal.current;
    const ed = editorRef.current;
    if (!r || !ed) {
      return;
    }
    const model = ed.getModel();
    if (!model || opens.current.get(r.path)?.model !== model) {
      return;
    }
    reveal.current = null;
    const mr = toMonacoRange(r.range);
    ed.revealRangeInCenterIfOutsideViewport(mr);
    ed.setSelection(mr);
    ed.focus();
  };

  const closeTab = (path: string) => {
    const open = opens.current.get(path);
    if (open && dirty.has(path) && !confirm(`Discard your edits to ${path}?`)) {
      return;
    }
    if (open) {
      if (open.version > 0) {
        lsp.current?.notify('textDocument/didClose', { textDocument: { uri: uriOf(path) } });
      }
      open.model.dispose();
      opens.current.delete(path);
      setDirty((d) => {
        const n = new Set(d);
        n.delete(path);
        return n;
      });
    }
    setTabs((t) => {
      const i = t.indexOf(path);
      const next = t.filter((x) => x !== path);
      if (active === path) {
        setActive(next[Math.min(i, next.length - 1)] ?? null);
      }
      return next;
    });
  };

  const downloadEdited = async () => {
    const edited = [...dirty].map((path) => ({ path, text: opens.current.get(path)!.model.getValue() }));
    try {
      const { zip, reencoded } = await api.exportEdited(project!.id, edited);
      save(zip, `${project?.name ?? 'project'}-edited.zip`);
      if (reencoded.length > 0) {
        setMessage({ text: reencodedNotice(reencoded), severity: 'warning' });
      }
    } catch (e) {
      fail(`Download failed: ${(e as Error).message}`);
    }
  };

  const onOpenFinding = (path: string, range: Range) => {
    const f = findings.find((x) => x.path === path && x.range === range) ?? null;
    setSelected(f);
    openFile(path, range);
  };

  // ---- derived ------------------------------------------------------------------------------

  const counts = useMemo(() => countsByPath(findings), [findings]);
  const activeFindings = useMemo(() => findings.filter((f) => f.path === active), [findings, active]);
  const activeSize = files.find((f) => f.path === active)?.size ?? 0;
  const projects = session?.projects ?? [];

  // ---- render -------------------------------------------------------------------------------

  return (
    <div
      class="app"
      onDragOver={(e) => {
        e.preventDefault();
      }}
      onDrop={(e) => {
        e.preventDefault();
        if (e.dataTransfer?.items.length) {
          doUpload(fromDrop(e.dataTransfer.items));
        }
      }}
    >
      <header class="toolbar">
        <span class="brand">
          <span class="rails" aria-hidden="true" />
          plca workbench
        </span>
        {projects.length > 0 ? (
          <label class="project-pick">
            <span class="visually-hidden">Project</span>
            <select
              value={project?.id ?? ''}
              onChange={(e) => {
                const p = projects.find((x) => x.id === (e.target as HTMLSelectElement).value);
                if (p) {
                  openProject(p);
                }
              }}
            >
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        {project ? <UploadButtons onPick={(p) => doUpload(p)} compact /> : null}
        {project ? (
          <nav class="views" aria-label="View">
            <button class={view === 'review' ? 'on' : ''} aria-pressed={view === 'review'} onClick={() => setView('review')}>
              Review
            </button>
            <button class={view === 'compare' ? 'on' : ''} aria-pressed={view === 'compare'} onClick={() => setView('compare')}>
              Compare versions
            </button>
          </nav>
        ) : null}
        <span class="spacer" />
        {dirty.size > 0 ? (
          <button onClick={downloadEdited} title="Your edits are never saved on the server">
            Download {dirty.size} edited file{dirty.size === 1 ? '' : 's'}
          </button>
        ) : null}
        {project ? (
          <details class="menu">
            <summary>Report</summary>
            <div class="menu-items">
              <a href={api.reportUrl(project.id, 'html')} download>
                HTML report
              </a>
              <a href={api.reportUrl(project.id, 'sarif')} download>
                SARIF (for CI and code review tools)
              </a>
              <a href={api.reportUrl(project.id, 'json')} download>
                JSON
              </a>
            </div>
          </details>
        ) : null}
        {project ? (
          <button onClick={closeProject} title="Close the project and delete it from the server">
            Close project
          </button>
        ) : null}
        <button
          class="link"
          onClick={async () => setAbout(about === null ? await api.about().catch(() => 'plca') : null)}
          aria-expanded={about !== null}
        >
          About
        </button>
      </header>

      {about !== null ? (
        <div class="about" role="dialog" aria-label="About">
          <button class="close" onClick={() => setAbout(null)}>
            Close
          </button>
          <h2>plca workbench</h2>
          <p>
            Uploaded projects stay on this server, are only visible to this browser session and are deleted when you close the page
            (after a short grace period for reloads) or close the project. Nothing is sent anywhere else.
          </p>
          <pre>{about}</pre>
        </div>
      ) : null}

      {!project ? (
        <Welcome onPick={(p) => doUpload(p)} limit={session?.limits.maxUploadBytes ?? 0} />
      ) : view === 'compare' ? (
        <Compare projects={projects} initialHead={project.id} onError={fail} />
      ) : (
        <main class="review">
          <aside class="side">
            <div class="side-tabs" role="tablist">
              <button role="tab" aria-selected={side === 'files'} class={side === 'files' ? 'on' : ''} onClick={() => setSide('files')}>
                Files <span class="n">{files.length}</span>
              </button>
              <button role="tab" aria-selected={side === 'outline'} class={side === 'outline' ? 'on' : ''} onClick={() => setSide('outline')}>
                Outline
              </button>
            </div>
            {side === 'files' ? (
              <FileTree files={files} counts={counts} active={active} dirty={dirty} onOpen={(p) => openFile(p, null)} />
            ) : (
              <Outline lsp={lspState} path={active} onReveal={(r) => active && openFile(active, r)} />
            )}
          </aside>
          <section class="center">
            <div class="tabs" role="tablist">
              {tabs.map((t) => (
                <div key={t} class={'tab' + (t === active ? ' on' : '')} role="tab" aria-selected={t === active}>
                  <button class="tab-name" onClick={() => setActive(t)} title={t}>
                    {t.slice(t.lastIndexOf('/') + 1)}
                    {dirty.has(t) ? <span class="dirty"> ●</span> : null}
                  </button>
                  <CountLamps counts={counts.get(t)} compact />
                  <button class="tab-close" onClick={() => closeTab(t)} aria-label={`Close ${t}`}>
                    ×
                  </button>
                </div>
              ))}
              {active && mayHaveDiagrams(active) ? (
                <div class="segmented mode" role="group" aria-label="Show">
                  <button class={mode === 'text' ? 'on' : ''} aria-pressed={mode === 'text'} onClick={() => setMode('text')}>
                    Text
                  </button>
                  <button class={mode === 'diagrams' ? 'on' : ''} aria-pressed={mode === 'diagrams'} onClick={() => setMode('diagrams')}>
                    Ladder and diagrams
                  </button>
                </div>
              ) : null}
            </div>
            {active === null ? (
              <div class="empty-state center-empty">
                <h2>{project.name}</h2>
                <p>
                  {files.length} file{files.length === 1 ? '' : 's'}
                  {project.skipped > 0 ? `, ${project.skipped} other file${project.skipped === 1 ? '' : 's'} left out` : ''}.
                </p>
                <p>Pick a file on the left, or a finding on the right.</p>
              </div>
            ) : null}
            {active !== null && activeSize > LARGE && mode === 'text' ? (
              <p class="banner">
                This file is large ({formatBytes(activeSize)}): it is read-only here and analysed from the uploaded copy.
              </p>
            ) : null}
            <CodeView
              visible={active !== null && mode === 'text'}
              path={active}
              opens={opens}
              editorRef={editorRef}
              onReady={applyReveal}
              readOnly={activeSize > LARGE}
            />
            {active !== null && mode === 'diagrams' ? (
              <Diagrams lsp={lspState} path={active} findings={activeFindings} onOpen={(p, r) => openFile(p, r)} />
            ) : null}
          </section>
          <Findings
            findings={findings}
            rules={rules}
            filter={filter}
            onFilter={setFilter}
            onOpen={onOpenFinding}
            selected={selected}
            analysing={analysing}
          />
        </main>
      )}

      <footer class="status" aria-live="polite">
        <span class={'conn ' + connection}>
          <span class="dot" aria-hidden="true" />
          {connection === 'ready'
            ? analysing
              ? 'Analysing'
              : 'Analysis up to date'
            : connection === 'connecting'
              ? 'Connecting'
              : connection === 'lost'
                ? 'Disconnected from the analysis server. Reload the page to reconnect.'
                : 'No project open'}
        </span>
        {project ? <CountLamps counts={counts.get('')} /> : null}
        <span class="spacer" />
        {upload ? (
          <span class="progress">
            Uploading {upload.name} <progress max={1} value={upload.fraction} />
          </span>
        ) : null}
        <span class="muted">{session ? `plca ${session.version}` : ''}</span>
      </footer>

      {message ? (
        <div class="toast" role="alert">
          <Lamp severity={message.severity} />
          <span>{message.text}</span>
          <button onClick={() => setMessage(null)}>Dismiss</button>
        </div>
      ) : null}
    </div>
  );
}

/** Files the server could not write in their original charset (see TextEncoder on the server). */
export function reencodedNotice(files: Reencoded[]): string {
  const list = files.map((f) => `${f.path} (${[...f.characters].join(' ')} not in ${f.original})`).join(', ');
  return `Saved as UTF-8 with BOM, because the edits contain characters the original encoding cannot hold: ${list}.`;
}

function sessionStorageGet(k: string): string | null {
  try {
    return sessionStorage.getItem(k);
  } catch {
    return null;
  }
}

function sessionStorageSet(k: string, v: string): void {
  try {
    sessionStorage.setItem(k, v);
  } catch {
    // private mode
  }
}

// ---- pieces -------------------------------------------------------------------------------------

function UploadButtons({ onPick, compact }: { onPick: (p: Promise<Picked>) => void; compact: boolean }) {
  const folder = useRef<HTMLInputElement>(null);
  const zip = useRef<HTMLInputElement>(null);
  const pick = (input: HTMLInputElement) => {
    if (input.files && input.files.length > 0) {
      onPick(fromFileList(input.files));
    }
    input.value = '';
  };
  return (
    <span class="upload-buttons">
      <button class={compact ? '' : 'primary'} onClick={() => folder.current?.click()}>
        {compact ? 'Open folder' : 'Choose a project folder'}
      </button>
      <button onClick={() => zip.current?.click()}>{compact ? 'Open zip' : 'Choose a zip file'}</button>
      {/* true, not '': Preact assigns webkitdirectory as a DOM property, and '' would turn it off */}
      <input ref={folder} type="file" hidden {...({ webkitdirectory: true } as any)} multiple onChange={(e) => pick(e.currentTarget)} />
      <input ref={zip} type="file" hidden accept=".zip,application/zip" onChange={(e) => pick(e.currentTarget)} />
    </span>
  );
}

function Welcome({ onPick, limit }: { onPick: (p: Promise<Picked>) => void; limit: number }) {
  const [over, setOver] = useState(false);
  return (
    <main
      class={'welcome' + (over ? ' over' : '')}
      onDragEnter={() => setOver(true)}
      onDragLeave={(e) => e.currentTarget === e.target && setOver(false)}
      onDrop={() => setOver(false)}
    >
      <div class="welcome-inner">
        <LadderMark />
        <h1>Review a PLC project</h1>
        <p class="lead">
          Drop a project export here: a folder or a zip with CODESYS or TwinCAT sources, Siemens SCL, a Rockwell L5X or PLCopen XML.
          You get every finding of the analyzer, the code with go-to-definition, and the ladder logic as drawn.
        </p>
        <UploadButtons onPick={onPick} compact={false} />
        <ul class="facts">
          <li>Your code stays on this server and is deleted when you close the page.</li>
          <li>Nothing is compiled, downloaded to a controller or sent elsewhere.</li>
          {limit > 0 ? <li>Uploads up to {formatBytes(limit)}; only PLC sources and plca.yaml are kept.</li> : null}
        </ul>
      </div>
    </main>
  );
}

/** Two rails and one rung: the workbench's mark. */
function LadderMark() {
  return (
    <svg class="ladder-mark" viewBox="0 0 160 56" aria-hidden="true">
      <line x1="6" y1="4" x2="6" y2="52" />
      <line x1="154" y1="4" x2="154" y2="52" />
      <path d="M6 28 H46 M58 28 H98 M120 28 H154" />
      <line x1="46" y1="18" x2="46" y2="38" />
      <line x1="58" y1="18" x2="58" y2="38" />
      <path d="M104 18 a14 14 0 0 0 0 20 M114 18 a14 14 0 0 1 0 20" />
      <circle class="lamp-dot" cx="52" cy="10" r="4" />
    </svg>
  );
}

function CodeView({
  visible,
  path,
  opens,
  editorRef,
  onReady,
  readOnly,
}: {
  visible: boolean;
  path: string | null;
  opens: { current: Map<string, Open> };
  editorRef: { current: monaco.editor.IStandaloneCodeEditor | null };
  onReady: () => void;
  readOnly: boolean;
}) {
  const host = useRef<HTMLDivElement>(null);
  const states = useRef(new Map<string, monaco.editor.ICodeEditorViewState | null>());
  const shown = useRef<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const ed = monaco.editor.create(host.current!, {
      model: null,
      theme: themeName(),
      automaticLayout: true,
      fontSize: 13,
      minimap: { enabled: true, renderCharacters: false },
      scrollBeyondLastLine: false,
      renderWhitespace: 'selection',
      'semanticHighlighting.enabled': false,
      fixedOverflowWidgets: true,
      largeFileOptimizations: true,
    });
    editorRef.current = ed;
    return () => {
      ed.dispose();
      editorRef.current = null;
    };
  }, []);

  // the model may still be loading: poll briefly until it exists
  useEffect(() => {
    const ed = editorRef.current;
    if (!ed) {
      return;
    }
    if (shown.current && shown.current !== path) {
      states.current.set(shown.current, ed.saveViewState());
    }
    const open = path ? opens.current.get(path) : undefined;
    if (!open) {
      ed.setModel(null);
      shown.current = null;
      if (path) {
        const t = window.setTimeout(() => setTick((x) => x + 1), 60);
        return () => window.clearTimeout(t);
      }
      return;
    }
    if (ed.getModel() !== open.model) {
      ed.setModel(open.model);
      const st = states.current.get(path!);
      if (st) {
        ed.restoreViewState(st);
      }
    }
    shown.current = path;
    ed.updateOptions({ readOnly, readOnlyMessage: { value: 'Large files are read-only in the workbench.' } });
    onReady();
  }, [path, tick, readOnly]);

  useEffect(() => {
    if (visible) {
      editorRef.current?.layout();
    }
  }, [visible]);

  return <div class="code" ref={host} style={{ display: visible ? 'block' : 'none' }} data-language-features={PLC_LANGUAGES.join(' ')} />;
}

interface OutlineItem {
  name: string;
  detail?: string;
  kind: number;
  range: Range;
  selectionRange: Range;
  children?: OutlineItem[];
}

function Outline({ lsp, path, onReveal }: { lsp: LspClient | null; path: string | null; onReveal: (r: Range) => void }) {
  const [items, setItems] = useState<OutlineItem[] | null>(null);
  useEffect(() => {
    setItems(null);
    if (!lsp || !path) {
      return;
    }
    lsp
      .request<OutlineItem[]>('textDocument/documentSymbol', { textDocument: { uri: uriOf(path) } })
      .then((x) => setItems(x ?? []))
      .catch(() => setItems([]));
  }, [lsp, path]);
  if (!path) {
    return <p class="empty">Open a file to see its outline.</p>;
  }
  if (items === null) {
    return <p class="empty">Loading…</p>;
  }
  if (items.length === 0) {
    return <p class="empty">Nothing to outline in this file.</p>;
  }
  const render = (list: OutlineItem[], depth: number): preact.JSX.Element[] =>
    list.flatMap((s, i) => [
      <button key={depth + ':' + i + s.name} class="outline-item" style={{ paddingLeft: `${8 + depth * 14}px` }} onClick={() => onReveal(s.selectionRange)}>
        <span class="name">{s.name}</span>
        {s.detail ? <span class="muted">{s.detail}</span> : null}
      </button>,
      ...render(s.children ?? [], depth + 1),
    ]);
  return <div class="outline">{render(items, 0)}</div>;
}
