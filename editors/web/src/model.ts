// Pure logic of the workbench (no DOM, no Monaco): URIs, findings, filters, counts. Unit-tested in test/unit.

/** The URI prefix the server serves the project under (LspSocket.PREFIX on the server). */
export const PREFIX = 'file:///project/';

export type Severity = 'error' | 'warning' | 'info' | 'hint';
export const SEVERITIES: Severity[] = ['error', 'warning', 'info', 'hint'];

export interface Position {
  line: number;
  character: number;
}

export interface Range {
  start: Position;
  end: Position;
}

/** A diagnostic as the panel shows it: a rule finding, or a syntax/model/configuration problem. */
export interface Finding {
  path: string;
  rule: string;
  severity: Severity;
  message: string;
  range: Range;
}

export interface RuleInfo {
  id: string;
  title: string;
  severity: string;
  category: string;
  dialects: string[];
  enabledByDefault: boolean;
  plcopen: string | null;
  markdown: string;
}

/** Characters a URI path segment may hold as they are (the server's Uris.encode keeps the same set). */
const SAFE = /[A-Za-z0-9\-._~!$&'()*+,;=:@]/;

function encodeSegment(segment: string): string {
  let out = '';
  for (const ch of segment) {
    if (ch.length === 1 && SAFE.test(ch)) {
      out += ch;
    } else {
      for (const b of new TextEncoder().encode(ch)) {
        out += '%' + b.toString(16).toUpperCase().padStart(2, '0');
      }
    }
  }
  return out;
}

/** The URI of a project file by its path ("plant/MAIN.st"), exactly as the server writes it. */
export function uriOf(path: string): string {
  return PREFIX + path.split('/').map(encodeSegment).join('/');
}

/** The project path of a URI the server sent, or null for a URI outside the project. */
export function pathOf(uri: string): string | null {
  if (!uri.startsWith(PREFIX)) {
    return null;
  }
  try {
    return uri
      .slice(PREFIX.length)
      .split('/')
      .map((s) => decodeURIComponent(s))
      .join('/');
  } catch {
    return null;
  }
}

/** LSP DiagnosticSeverity (1..4) as a name. */
export function severityOf(n: number | undefined): Severity {
  switch (n) {
    case 1:
      return 'error';
    case 2:
      return 'warning';
    case 4:
      return 'hint';
    default:
      return 'info';
  }
}

export function before(a: Position, b: Position): boolean {
  return a.line < b.line || (a.line === b.line && a.character < b.character);
}

/** True when {@code inner} lies within {@code outer} (ends included). */
export function contains(outer: Range, inner: Range): boolean {
  return !before(inner.start, outer.start) && !before(outer.end, inner.end);
}

/** True when the two ranges share at least one position. */
export function overlaps(a: Range, b: Range): boolean {
  return !before(a.end, b.start) && !before(b.end, a.start);
}

/** "l1,c1,l2,c2" of a drawing's data-range attribute. */
export function parseRange(s: string | null | undefined): Range | null {
  if (!s) {
    return null;
  }
  const n = s.split(',').map(Number);
  if (n.length !== 4 || n.some((x) => !Number.isFinite(x))) {
    return null;
  }
  return { start: { line: n[0], character: n[1] }, end: { line: n[2], character: n[3] } };
}

/** Where a diagnostic code comes from: a rule category, or the analyzer stage that reported it. */
export function categoryOf(code: string, rules: Map<string, RuleInfo>): string {
  const r = rules.get(code);
  if (r) {
    return r.category;
  }
  if (code.startsWith('PLC-CFG') || code.startsWith('PLC-SUP')) {
    return 'configuration';
  }
  if (code.startsWith('PLC-SEM')) {
    return 'model';
  }
  if (code.startsWith('PLC-INT')) {
    return 'internal';
  }
  return 'syntax'; // PLC-SYN, -XML, -L5X, -SCL, -TC, -IO, -CORE: reading the input
}

export interface Filter {
  severities: Set<Severity>;
  /** Shown rule ids or diagnostic codes; empty: all. */
  rules: Set<string>;
  categories: Set<string>;
  text: string;
}

export function emptyFilter(): Filter {
  return { severities: new Set(SEVERITIES), rules: new Set(), categories: new Set(), text: '' };
}

export function matches(f: Finding, filter: Filter, rules: Map<string, RuleInfo>): boolean {
  if (!filter.severities.has(f.severity)) {
    return false;
  }
  if (filter.rules.size > 0 && !filter.rules.has(f.rule)) {
    return false;
  }
  if (filter.categories.size > 0 && !filter.categories.has(categoryOf(f.rule, rules))) {
    return false;
  }
  const t = filter.text.trim().toLowerCase();
  if (t) {
    const title = rules.get(f.rule)?.title ?? '';
    const hay = (f.message + ' ' + f.path + ' ' + f.rule + ' ' + title).toLowerCase();
    return t.split(/\s+/).every((w) => hay.includes(w));
  }
  return true;
}

const ORDER: Record<Severity, number> = { error: 0, warning: 1, info: 2, hint: 3 };

/** Findings in reading order: path, then position. */
export function sortFindings(list: Finding[]): Finding[] {
  return [...list].sort(
    (a, b) =>
      a.path.localeCompare(b.path) ||
      a.range.start.line - b.range.start.line ||
      a.range.start.character - b.range.start.character ||
      ORDER[a.severity] - ORDER[b.severity] ||
      a.rule.localeCompare(b.rule),
  );
}

export interface Group {
  key: string;
  findings: Finding[];
}

/** Groups by file (reading order) or by rule (most severe and most frequent first). */
export function group(list: Finding[], by: 'file' | 'rule'): Group[] {
  const map = new Map<string, Finding[]>();
  for (const f of sortFindings(list)) {
    const k = by === 'file' ? f.path : f.rule;
    let g = map.get(k);
    if (!g) {
      map.set(k, (g = []));
    }
    g.push(f);
  }
  const groups = [...map.entries()].map(([key, findings]) => ({ key, findings }));
  if (by === 'rule') {
    const worst = (g: Group) => Math.min(...g.findings.map((f) => ORDER[f.severity]));
    groups.sort((a, b) => worst(a) - worst(b) || b.findings.length - a.findings.length || a.key.localeCompare(b.key));
  }
  return groups;
}

export interface Counts {
  error: number;
  warning: number;
  info: number;
  hint: number;
}

export function zero(): Counts {
  return { error: 0, warning: 0, info: 0, hint: 0 };
}

/** Counts per file and per folder (every ancestor of a file, "" for the root). */
export function countsByPath(list: Finding[]): Map<string, Counts> {
  const out = new Map<string, Counts>();
  const add = (k: string, s: Severity) => {
    let c = out.get(k);
    if (!c) {
      out.set(k, (c = zero()));
    }
    c[s]++;
  };
  for (const f of list) {
    add(f.path, f.severity);
    let i = f.path.lastIndexOf('/');
    while (i > 0) {
      add(f.path.slice(0, i) + '/', f.severity);
      i = f.path.lastIndexOf('/', i - 1);
    }
    add('', f.severity);
  }
  return out;
}

/** The findings of a file that lie in a diagram's text range: counts, total and the first in text order. */
export function findingsIn(list: Finding[], range: Range): { counts: Counts; total: number; first: Finding | null } {
  const counts = zero();
  let total = 0;
  let first: Finding | null = null;
  for (const f of list) {
    if (contains(range, f.range) || overlaps(range, f.range)) {
      counts[f.severity]++;
      total++;
      if (!first || before(f.range.start, first.range.start)) {
        first = f;
      }
    }
  }
  return { counts, total, first };
}

/** Monaco language of a project file; PLCopen is told by its namespace, as the analyzer does. */
export function languageOf(path: string, head = ''): string {
  const lower = path.toLowerCase();
  const ext = lower.slice(lower.lastIndexOf('.'));
  switch (ext) {
    case '.st':
      return 'iec-st';
    case '.scl':
    case '.db':
    case '.udt':
      return 'siemens-scl';
    case '.tcpou':
    case '.tcdut':
    case '.tcgvl':
    case '.tcio':
    case '.tctto':
      return 'twincat-xml';
    case '.l5x':
      return 'rockwell-l5x';
    case '.xml':
      return head.includes('plcopen.org/xml/tc6') ? 'plcopen-xml' : 'xml-plain';
    case '.yaml':
    case '.yml':
      return 'yaml-plain';
    default:
      return 'plaintext';
  }
}

/** Files that can hold ladder or LD/FBD drawings. */
export function mayHaveDiagrams(path: string): boolean {
  return /\.(l5x|xml)$/i.test(path);
}

export interface TreeNode {
  name: string;
  /** "a/b/" for folders, "a/b/c.st" for files. */
  path: string;
  folder: boolean;
  size?: number;
  children: TreeNode[];
}

/** A folder tree of the project's files; folders first, then by name (case-insensitive). */
export function buildTree(files: { path: string; size: number }[]): TreeNode {
  const root: TreeNode = { name: '', path: '', folder: true, children: [] };
  for (const f of files) {
    const parts = f.path.split('/');
    let node = root;
    for (let i = 0; i < parts.length - 1; i++) {
      const p = parts.slice(0, i + 1).join('/') + '/';
      let next = node.children.find((c) => c.folder && c.path === p);
      if (!next) {
        next = { name: parts[i], path: p, folder: true, children: [] };
        node.children.push(next);
      }
      node = next;
    }
    node.children.push({ name: parts[parts.length - 1], path: f.path, folder: false, size: f.size, children: [] });
  }
  const sort = (n: TreeNode) => {
    n.children.sort((a, b) => Number(b.folder) - Number(a.folder) || a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
    n.children.forEach(sort);
  };
  sort(root);
  // a single top folder (the uploaded folder itself) is opened by default by the view, not removed here
  return root;
}

/** Keeps files whose path matches every word of the query (case-insensitive). */
export function filterFiles<T extends { path: string }>(files: T[], query: string): T[] {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return files;
  }
  return files.filter((f) => words.every((w) => f.path.toLowerCase().includes(w)));
}

/** What a folder upload keeps: sources the analyzer reads, its configuration and baselines (as the server). */
export function uploadable(path: string): boolean {
  const name = path.slice(path.lastIndexOf('/') + 1).toLowerCase();
  if (path.split('/').some((s) => s.startsWith('.') && s.length > 1) || path.includes('__MACOSX/')) {
    return false;
  }
  return (
    /\.(st|tcpou|tcdut|tcgvl|tcio|tctto|scl|db|udt|l5x|xml|json)$/.test(name) || name === 'plca.yaml'
  );
}

export function formatBytes(n: number): string {
  if (n < 1024) {
    return `${n} B`;
  }
  if (n < 1024 * 1024) {
    return `${(n / 1024).toFixed(n < 10240 ? 1 : 0)} KB`;
  }
  const mb = n / 1024 / 1024;
  return `${mb >= 100 || Number.isInteger(mb) ? Math.round(mb) : mb.toFixed(1)} MB`;
}

/** The server's progress while it loads a project (LSP work-done progress). */
export interface LoadProgress {
  title: string;
  message: string | null;
  percentage: number;
}

/** Folds an LSP work-done progress value into the shown state; null after "end". */
export function nextProgress(old: LoadProgress | null, v: any): LoadProgress | null {
  switch (v?.kind) {
    case 'begin':
      return { title: v.title, message: v.message ?? null, percentage: v.percentage ?? 0 };
    case 'report':
      return old ? { ...old, message: v.message ?? old.message, percentage: v.percentage ?? old.percentage } : old;
    case 'end':
      return null;
    default:
      return old;
  }
}

