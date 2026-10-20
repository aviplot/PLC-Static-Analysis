// The punch list: every finding of the project, filtered and grouped, click to go to the code.

import { useMemo, useState } from 'preact/hooks';
import {
  type Filter,
  type Finding,
  type Range,
  type RuleInfo,
  type Severity,
  categoryOf,
  group,
  matches,
} from '../model.ts';
import { Lamp, Marker } from './lamps.tsx';
import { Markdown } from './markdown.tsx';
import { VirtualList } from './virtual.tsx';

type Row = { kind: 'group'; key: string; count: number; worst: Severity } | { kind: 'finding'; finding: Finding };

interface Props {
  findings: Finding[];
  rules: Map<string, RuleInfo>;
  filter: Filter;
  onFilter: (f: Filter) => void;
  onOpen: (path: string, range: Range) => void;
  selected: Finding | null;
  analysing: boolean;
}

const SEVERITY_LABEL: Record<Severity, string> = { error: 'Errors', warning: 'Warnings', info: 'Information', hint: 'Hints' };

export function Findings({ findings, rules, filter, onFilter, onOpen, selected, analysing }: Props) {
  const [by, setBy] = useState<'file' | 'rule'>('file');
  const [doc, setDoc] = useState<string | null>(null);
  const [folded, setFolded] = useState<Set<string>>(new Set());

  const bySeverity = useMemo(() => {
    const c: Record<Severity, number> = { error: 0, warning: 0, info: 0, hint: 0 };
    findings.forEach((f) => c[f.severity]++);
    return c;
  }, [findings]);
  const shown = useMemo(() => findings.filter((f) => matches(f, filter, rules)), [findings, filter, rules]);
  const codes = useMemo(() => {
    const m = new Map<string, number>();
    findings.forEach((f) => m.set(f.rule, (m.get(f.rule) ?? 0) + 1));
    return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [findings]);
  const categories = useMemo(() => [...new Set(findings.map((f) => categoryOf(f.rule, rules)))].sort(), [findings, rules]);
  const rows = useMemo(() => {
    const out: Row[] = [];
    for (const g of group(shown, by)) {
      const worst = (['error', 'warning', 'info', 'hint'] as Severity[]).find((s) => g.findings.some((f) => f.severity === s))!;
      out.push({ kind: 'group', key: g.key, count: g.findings.length, worst });
      if (!folded.has(g.key)) {
        g.findings.forEach((f) => out.push({ kind: 'finding', finding: f }));
      }
    }
    return out;
  }, [shown, by, folded]);
  const selectedIndex = selected ? rows.findIndex((r) => r.kind === 'finding' && r.finding === selected) : -1;

  const toggleSeverity = (s: Severity) => {
    const next = new Set(filter.severities);
    next.has(s) ? next.delete(s) : next.add(s);
    onFilter({ ...filter, severities: next });
  };
  const fold = (key: string) => {
    const next = new Set(folded);
    next.has(key) ? next.delete(key) : next.add(key);
    setFolded(next);
  };

  return (
    <section class="findings" aria-label="Findings">
      <header class="findings-head">
        <div class="severity-toggles" role="group" aria-label="Show severities">
          {(['error', 'warning', 'info'] as Severity[]).map((s) => (
            <button
              key={s}
              class={'toggle' + (filter.severities.has(s) ? ' on' : '')}
              aria-pressed={filter.severities.has(s)}
              onClick={() => toggleSeverity(s)}
              title={`Show ${SEVERITY_LABEL[s].toLowerCase()}`}
            >
              <Lamp severity={s} />
              <span class="n">{bySeverity[s]}</span>
              <span class="what">{SEVERITY_LABEL[s]}</span>
            </button>
          ))}
        </div>
        <div class="filters">
          <input
            class="search"
            type="search"
            placeholder="Search messages, files, rules"
            aria-label="Search findings"
            value={filter.text}
            onInput={(e) => onFilter({ ...filter, text: (e.target as HTMLInputElement).value })}
          />
          <select
            aria-label="Rule"
            value={filter.rules.size === 1 ? [...filter.rules][0] : ''}
            onChange={(e) => {
              const v = (e.target as HTMLSelectElement).value;
              onFilter({ ...filter, rules: v ? new Set([v]) : new Set() });
            }}
          >
            <option value="">All rules and checks</option>
            {codes.map(([code, n]) => (
              <option key={code} value={code}>
                {code} {rules.get(code)?.title ?? ''} ({n})
              </option>
            ))}
          </select>
          <select
            aria-label="Category"
            value={filter.categories.size === 1 ? [...filter.categories][0] : ''}
            onChange={(e) => {
              const v = (e.target as HTMLSelectElement).value;
              onFilter({ ...filter, categories: v ? new Set([v]) : new Set() });
            }}
          >
            <option value="">All categories</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c[0].toUpperCase() + c.slice(1)}
              </option>
            ))}
          </select>
          <div class="segmented" role="group" aria-label="Group findings by">
            <button aria-pressed={by === 'file'} class={by === 'file' ? 'on' : ''} onClick={() => setBy('file')}>
              By file
            </button>
            <button aria-pressed={by === 'rule'} class={by === 'rule' ? 'on' : ''} onClick={() => setBy('rule')}>
              By rule
            </button>
          </div>
        </div>
        <p class="summary" aria-live="polite">
          {analysing ? 'Analysing… ' : ''}
          {shown.length === findings.length
            ? `${findings.length} finding${findings.length === 1 ? '' : 's'}`
            : `${shown.length} of ${findings.length} findings shown`}
        </p>
      </header>
      {doc !== null ? (
        <div class="rule-doc">
          <button class="close" onClick={() => setDoc(null)} aria-label="Close rule documentation">
            Close
          </button>
          <Markdown text={rules.get(doc)?.markdown ?? `No documentation for ${doc}.`} />
        </div>
      ) : null}
      {findings.length === 0 ? (
        <p class="empty">{analysing ? 'Waiting for the first analysis.' : 'No findings in this project.'}</p>
      ) : shown.length === 0 ? (
        <p class="empty">No finding matches these filters.</p>
      ) : (
        <VirtualList
          items={rows}
          height={(r) => (r.kind === 'group' ? 30 : 52)}
          reveal={selectedIndex}
          label="Findings"
          render={(r) =>
            r.kind === 'group' ? (
              <div class="group">
                <button class="group-key" onClick={() => fold(r.key)} aria-expanded={!folded.has(r.key)}>
                  <span class={'twisty' + (folded.has(r.key) ? '' : ' open')} aria-hidden="true" />
                  {by === 'rule' ? <Marker id={r.key} /> : <Lamp severity={r.worst} />}
                  <span class="key">{by === 'rule' ? rules.get(r.key)?.title ?? r.key : r.key}</span>
                  <span class="n">{r.count}</span>
                </button>
                {by === 'rule' && rules.has(r.key) ? (
                  <button class="link" onClick={() => setDoc(r.key)}>
                    About this rule
                  </button>
                ) : null}
              </div>
            ) : (
              <button
                class={'finding' + (r.finding === selected ? ' selected' : '')}
                onClick={() => onOpen(r.finding.path, r.finding.range)}
              >
                <Lamp severity={r.finding.severity} />
                <span class="message">{r.finding.message}</span>
                <span class="where">
                  <Marker id={r.finding.rule} title={rules.get(r.finding.rule)?.title} />
                  {by === 'rule' ? r.finding.path + ' ' : ''}line {r.finding.range.start.line + 1}
                </span>
              </button>
            )
          }
        />
      )}
    </section>
  );
}
