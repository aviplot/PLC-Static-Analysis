// Two versions of a project side by side: which findings are new, which are fixed, and the file differences with the
// findings marked on each side (fixed ones on the old text, new ones on the new text).

import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { type CompareItem, type Comparison, type ProjectInfo, api } from '../api.ts';
import { monaco, setMarkers, themeName } from '../editor.ts';
import { type Finding, languageOf, severityOf } from '../model.ts';
import { Lamp, Marker } from './lamps.tsx';

interface Props {
  projects: ProjectInfo[];
  initialHead: string | null;
  onError: (message: string) => void;
}

function toFinding(i: CompareItem): Finding {
  const sev = i.severity === 'error' ? 1 : i.severity === 'warning' ? 2 : 3;
  return {
    path: i.path,
    rule: i.rule,
    severity: severityOf(sev),
    message: i.message,
    range: { start: { line: i.startLine, character: i.startCharacter }, end: { line: i.endLine, character: i.endCharacter } },
  };
}

export function Compare({ projects, initialHead, onError }: Props) {
  const others = projects.filter((p) => p.id !== initialHead);
  const [base, setBase] = useState<string>(others[0]?.id ?? '');
  const [head, setHead] = useState<string>(initialHead ?? projects[0]?.id ?? '');
  const [result, setResult] = useState<Comparison | null>(null);
  const [busy, setBusy] = useState(false);
  const [file, setFile] = useState<string | null>(null);
  const [onlyChanged, setOnlyChanged] = useState(true);

  const run = async () => {
    if (!base || !head || base === head) {
      return;
    }
    setBusy(true);
    setResult(null);
    setFile(null);
    try {
      const r = await api.compare(base, head);
      setResult(r);
      const first = r.files.find((f) => f.added > 0 || f.fixed > 0) ?? r.files.find((f) => f.change !== 'unchanged');
      setFile(first?.path ?? null);
    } catch (e: any) {
      onError(`Comparison failed: ${e.message ?? e}`);
    } finally {
      setBusy(false);
    }
  };

  if (projects.length < 2) {
    return (
      <div class="compare empty-state">
        <h2>Compare two versions</h2>
        <p>Upload the older and the newer export of the same project. The comparison shows which findings are new, which were fixed, and what changed in each file.</p>
        <p class="muted">One project is open in this session; upload one more to compare.</p>
      </div>
    );
  }
  const name = (id: string) => projects.find((p) => p.id === id)?.name ?? id;
  const files = result ? result.files.filter((f) => !onlyChanged || f.change !== 'unchanged' || f.added > 0 || f.fixed > 0) : [];

  return (
    <div class="compare">
      <header class="compare-head">
        <label>
          Older version
          <select value={base} onChange={(e) => setBase((e.target as HTMLSelectElement).value)}>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Newer version
          <select value={head} onChange={(e) => setHead((e.target as HTMLSelectElement).value)}>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <button class="primary" disabled={busy || !base || !head || base === head} onClick={run}>
          {busy ? 'Comparing…' : 'Compare'}
        </button>
        {base && base === head ? <span class="muted">Pick two different projects.</span> : null}
      </header>
      {result ? (
        <div class="compare-body">
          <aside class="compare-side">
            <div class="tally">
              <p>
                <strong class="new">{result.added}</strong> new
              </p>
              <p>
                <strong class="fixed">{result.fixed}</strong> fixed
              </p>
              <p>
                <strong>{result.unchanged}</strong> unchanged
              </p>
            </div>
            {result.rules.filter((r) => r.added || r.fixed).length > 0 ? (
              <table class="rule-delta">
                <caption>By rule</caption>
                <thead>
                  <tr>
                    <th scope="col">Rule</th>
                    <th scope="col">New</th>
                    <th scope="col">Fixed</th>
                  </tr>
                </thead>
                <tbody>
                  {result.rules
                    .filter((r) => r.added || r.fixed)
                    .map((r) => (
                      <tr key={r.rule}>
                        <td>
                          <Marker id={r.rule} />
                        </td>
                        <td class={r.added ? 'new' : ''}>{r.added || ''}</td>
                        <td class={r.fixed ? 'fixed' : ''}>{r.fixed || ''}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            ) : null}
            <div class="files-head">
              <h3>Files</h3>
              <label class="check">
                <input type="checkbox" checked={onlyChanged} onChange={(e) => setOnlyChanged((e.target as HTMLInputElement).checked)} />
                Changed only
              </label>
            </div>
            <ul class="file-delta">
              {files.map((f) => (
                <li key={f.path}>
                  <button class={f.path === file ? 'active' : ''} onClick={() => setFile(f.path)}>
                    <span class={'change ' + f.change}>{f.change}</span>
                    <span class="name" title={f.path}>
                      {f.path}
                    </span>
                    {f.added ? <span class="new">+{f.added}</span> : null}
                    {f.fixed ? <span class="fixed">−{f.fixed}</span> : null}
                  </button>
                </li>
              ))}
              {files.length === 0 ? <li class="muted">No file changed.</li> : null}
            </ul>
          </aside>
          {file ? (
            <DiffPane
              key={file}
              base={base}
              head={head}
              path={file}
              change={result.files.find((f) => f.path === file)!.change}
              items={result.findings.filter((i) => i.path === file)}
              baseName={name(base)}
              headName={name(head)}
              onError={onError}
            />
          ) : (
            <p class="empty">Pick a file to see its differences.</p>
          )}
        </div>
      ) : busy ? (
        <p class="empty">Analysing both versions…</p>
      ) : (
        <p class="empty">Pick the older and the newer version, then compare.</p>
      )}
    </div>
  );
}

interface DiffProps {
  base: string;
  head: string;
  path: string;
  change: string;
  items: CompareItem[];
  baseName: string;
  headName: string;
  onError: (message: string) => void;
}

function DiffPane({ base, head, path, change, items, baseName, headName, onError }: DiffProps) {
  const host = useRef<HTMLDivElement>(null);
  const list = useMemo(() => items.filter((i) => i.status !== 'unchanged'), [items]);
  const [editor, setEditor] = useState<monaco.editor.IStandaloneDiffEditor | null>(null);

  useEffect(() => {
    let disposed = false;
    const models: monaco.editor.ITextModel[] = [];
    const ed = monaco.editor.createDiffEditor(host.current!, {
      theme: themeName(),
      readOnly: true,
      originalEditable: false,
      automaticLayout: true,
      renderSideBySide: host.current!.clientWidth > 900,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
    });
    setEditor(ed);
    (async () => {
      try {
        const [a, b] = await Promise.all([
          change === 'added' ? '' : api.file(base, path),
          change === 'removed' ? '' : api.file(head, path),
        ]);
        if (disposed) {
          return;
        }
        const language = languageOf(path, (a || b).slice(0, 4096));
        const original = monaco.editor.createModel(a, language);
        const modified = monaco.editor.createModel(b, language);
        models.push(original, modified);
        ed.setModel({ original, modified });
        setMarkers(original, items.filter((i) => i.status === 'fixed').map(toFinding), 'plca-compare');
        setMarkers(modified, items.filter((i) => i.status === 'new').map(toFinding), 'plca-compare');
      } catch (e: any) {
        onError(`Could not load ${path}: ${e.message ?? e}`);
      }
    })();
    return () => {
      disposed = true;
      ed.dispose();
      models.forEach((m) => m.dispose());
    };
  }, [base, head, path]);

  const reveal = (i: CompareItem) => {
    const side = i.status === 'fixed' ? editor?.getOriginalEditor() : editor?.getModifiedEditor();
    side?.revealLineInCenter(i.startLine + 1);
    side?.setPosition({ lineNumber: i.startLine + 1, column: i.startCharacter + 1 });
    side?.focus();
  };

  return (
    <div class="diff-pane">
      <div class="diff-titles">
        <span>{baseName}</span>
        <span>{headName}</span>
      </div>
      <div class="diff-editor" ref={host} />
      {list.length > 0 ? (
        <ul class="diff-findings" aria-label="New and fixed findings in this file">
          {list.map((i, k) => (
            <li key={k}>
              <button onClick={() => reveal(i)}>
                <span class={'status ' + i.status}>{i.status === 'new' ? 'New' : 'Fixed'}</span>
                <Lamp severity={severityOf(i.severity === 'error' ? 1 : i.severity === 'warning' ? 2 : 3)} />
                <span class="message">{i.message}</span>
                <Marker id={i.rule} />
                <span class="muted">line {i.startLine + 1}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
