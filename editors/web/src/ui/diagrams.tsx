// Ladder rungs (L5X) and LD/FBD networks (PLCopen) drawn by the server (plca/diagram), loaded a page at a time.
// Findings are drawn on the smallest element whose source range holds them; hover asks the language server, click
// goes to the text.

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import type { LspClient } from '../lsp.ts';
import { type Finding, type Range, contains, overlaps, parseRange, uriOf } from '../model.ts';
import { Lamp } from './lamps.tsx';
import { Markdown } from './markdown.tsx';

interface DiagramInfo {
  name: string;
  kind: string;
  language: string;
  parts: number;
}

interface Part {
  number: number;
  svg: string;
}

const PAGE = 40;

interface Props {
  lsp: LspClient | null;
  path: string;
  findings: Finding[];
  onOpen: (path: string, range: Range) => void;
}

interface Tip {
  x: number;
  y: number;
  hover: string | null;
  findings: Finding[];
}

export function Diagrams({ lsp, path, findings, onOpen }: Props) {
  const [list, setList] = useState<DiagramInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [parts, setParts] = useState<Part[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [tip, setTip] = useState<Tip | null>(null);
  const body = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const generation = useRef(0);

  useEffect(() => {
    setList(null);
    setSelected(null);
    setError(null);
    if (!lsp) {
      return;
    }
    lsp
      .request<DiagramInfo[] | null>('plca/diagrams', { uri: uriOf(path) })
      .then((l) => {
        setList(l ?? []);
        if (l && l.length > 0) {
          setSelected(l[0].name);
        }
      })
      .catch((e) => setError(String(e.message ?? e)));
  }, [lsp, path]);

  const load = async (name: string, from: number, reset: boolean) => {
    if (!lsp) {
      return;
    }
    const g = ++generation.current;
    setLoading(true);
    try {
      const page = await lsp.request<{ total: number; parts: Part[] } | null>('plca/diagram', {
        uri: uriOf(path),
        name,
        from,
        count: PAGE,
      });
      if (g !== generation.current) {
        return;
      }
      setTotal(page?.total ?? 0);
      setParts((old) => (reset ? (page?.parts ?? []) : [...old, ...(page?.parts ?? [])]));
    } catch (e: any) {
      setError(String(e.message ?? e));
    } finally {
      if (g === generation.current) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    setParts([]);
    setTotal(0);
    if (selected) {
      body.current?.scrollTo({ top: 0 });
      load(selected, 0, true);
    }
  }, [selected, lsp]);

  // the next page when the end of the list comes into view
  useEffect(() => {
    const s = sentinel.current;
    if (!s || !selected) {
      return;
    }
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting) && !loading && parts.length < total) {
        load(selected, parts.length, false);
      }
    });
    io.observe(s);
    return () => io.disconnect();
  }, [parts, total, loading, selected]);

  // findings onto the drawings
  useLayoutEffect(() => {
    const root = body.current;
    if (!root) {
      return;
    }
    root.querySelectorAll('.has-finding').forEach((e) => e.classList.remove('has-finding', 'sev-error', 'sev-warning', 'sev-info', 'sev-hint'));
    const elements = Array.from(root.querySelectorAll<SVGElement>('g[data-range]')).map((e) => ({
      e,
      r: parseRange(e.getAttribute('data-range')),
    }));
    for (const f of findings) {
      let best: { e: SVGElement; r: Range } | null = null;
      for (const x of elements) {
        if (x.r && (contains(x.r, f.range) || overlaps(x.r, f.range)) && x.e.matches('.ins, .el')) {
          if (!best || contains(best.r, x.r)) {
            best = { e: x.e, r: x.r };
          }
        }
      }
      if (best) {
        const rank = ['error', 'warning', 'info', 'hint'];
        const current = rank.findIndex((s) => best!.e.classList.contains('sev-' + s));
        if (current < 0 || rank.indexOf(f.severity) < current) {
          best.e.classList.remove('sev-error', 'sev-warning', 'sev-info', 'sev-hint');
          best.e.classList.add('has-finding', 'sev-' + f.severity);
        }
      }
    }
  }, [parts, findings]);

  const target = (ev: Event) => {
    const t = ev.target as Element | null;
    return t?.closest<SVGElement>('text[data-range], g[data-range]') ?? null;
  };
  const hoverTimer = useRef<number>(0);
  const onMove = (ev: MouseEvent) => {
    const el = target(ev);
    window.clearTimeout(hoverTimer.current);
    if (!el) {
      setTip(null);
      return;
    }
    const range = parseRange(el.getAttribute('data-range'));
    const group = el.closest('g.ins, g.el');
    const groupRange = parseRange(group?.getAttribute('data-range'));
    const x = ev.clientX;
    const y = ev.clientY;
    hoverTimer.current = window.setTimeout(async () => {
      const here = findings.filter((f) => groupRange && (contains(groupRange, f.range) || overlaps(groupRange, f.range)));
      let hover: string | null = null;
      if (range && lsp && el.tagName.toLowerCase() === 'text') {
        try {
          const h = await lsp.request<any>('textDocument/hover', {
            textDocument: { uri: uriOf(path) },
            position: range.start,
          });
          hover = h ? (typeof h.contents === 'string' ? h.contents : h.contents.value) : null;
        } catch {
          hover = null;
        }
      }
      if (hover || here.length > 0) {
        setTip({ x, y, hover, findings: here });
      } else {
        setTip(null);
      }
    }, 250);
  };
  const onClick = (ev: MouseEvent) => {
    const el = target(ev);
    const range = parseRange(el?.getAttribute('data-range'));
    if (range) {
      onOpen(path, range);
    }
  };

  if (error) {
    return <p class="empty">The drawings could not be loaded: {error}</p>;
  }
  if (list === null) {
    return <p class="empty">Loading the drawings…</p>;
  }
  if (list.length === 0) {
    return <p class="empty">This file has no ladder routines or LD/FBD bodies.</p>;
  }
  return (
    <div class="diagrams">
      <nav class="diagram-list" aria-label="Routines and POUs">
        {list.map((d) => (
          <button
            key={d.name}
            class={d.name === selected ? 'active' : ''}
            aria-current={d.name === selected}
            onClick={() => setSelected(d.name)}
            title={`${d.kind.toLowerCase()}, ${d.language}, ${d.parts} ${d.language === 'RLL' ? 'rungs' : 'networks'}`}
          >
            <span class="name">{d.name}</span>
            <span class="n">{d.parts}</span>
          </button>
        ))}
      </nav>
      <div
        class="diagram-body"
        ref={body}
        onMouseMove={onMove}
        onMouseLeave={() => {
          window.clearTimeout(hoverTimer.current);
          setTip(null);
        }}
        onClick={onClick}
      >
        {parts.map((p, i) => (
          <figure key={selected + ':' + i} class="part">
            {/* SVG drawn by the plca server; every text in it is escaped there */}
            <div class="svg" dangerouslySetInnerHTML={{ __html: p.svg }} />
          </figure>
        ))}
        <div ref={sentinel} class="sentinel">
          {loading ? 'Loading…' : parts.length < total ? '' : total > 0 ? `${total} ${total === 1 ? 'part' : 'parts'}` : ''}
        </div>
      </div>
      {tip ? (
        <div class="tip" style={{ left: `${Math.min(tip.x + 14, innerWidth - 380)}px`, top: `${tip.y + 16}px` }} role="tooltip">
          {tip.findings.map((f, i) => (
            <p key={i} class="tip-finding">
              <Lamp severity={f.severity} /> {f.message} <span class="code">{f.rule}</span>
            </p>
          ))}
          {tip.hover ? <Markdown text={tip.hover} /> : null}
        </div>
      ) : null}
    </div>
  );
}
