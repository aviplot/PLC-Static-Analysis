// The project's files as a folder tree with finding counts; windowed, so exports with thousands of files stay fast.

import { useMemo, useState } from 'preact/hooks';
import type { FileInfo } from '../api.ts';
import { type Counts, type TreeNode, buildTree, filterFiles, formatBytes } from '../model.ts';
import { CountLamps } from './lamps.tsx';
import { VirtualList } from './virtual.tsx';

interface Row {
  node: TreeNode;
  depth: number;
}

interface Props {
  files: FileInfo[];
  counts: Map<string, Counts>;
  active: string | null;
  dirty: Set<string>;
  onOpen: (path: string) => void;
}

export function FileTree({ files, counts, active, dirty, onOpen }: Props) {
  const [query, setQuery] = useState('');
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const shown = useMemo(() => filterFiles(files, query), [files, query]);
  const tree = useMemo(() => buildTree(shown), [shown]);
  const rows = useMemo(() => {
    const out: Row[] = [];
    const visit = (n: TreeNode, depth: number) => {
      for (const c of n.children) {
        out.push({ node: c, depth });
        // a filter shows every match: folders stay open while one is typed
        if (c.folder && (query || !collapsed.has(c.path))) {
          visit(c, depth + 1);
        }
      }
    };
    visit(tree, 0);
    return out;
  }, [tree, collapsed, query]);
  const toggle = (path: string) => {
    const next = new Set(collapsed);
    next.has(path) ? next.delete(path) : next.add(path);
    setCollapsed(next);
  };
  const activeIndex = active ? rows.findIndex((r) => r.node.path === active) : -1;

  return (
    <div class="tree">
      <input
        class="search"
        type="search"
        placeholder="Filter files"
        aria-label="Filter files"
        value={query}
        onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
      />
      {shown.length === 0 ? (
        <p class="empty">No file matches “{query}”.</p>
      ) : (
        <VirtualList
          items={rows}
          height={() => 26}
          reveal={activeIndex}
          label="Project files"
          render={(r) => {
            const n = r.node;
            const open = n.folder && (query !== '' || !collapsed.has(n.path));
            return (
              <button
                class={'node' + (n.folder ? ' folder' : ' file') + (n.path === active ? ' active' : '')}
                style={{ paddingLeft: `${8 + r.depth * 14}px` }}
                onClick={() => (n.folder ? toggle(n.path) : onOpen(n.path))}
                title={n.folder ? n.path : `${n.path} (${formatBytes(n.size ?? 0)})`}
                aria-expanded={n.folder ? open : undefined}
              >
                <span class={'twisty' + (n.folder ? (open ? ' open' : '') : ' none')} aria-hidden="true" />
                <span class="name">
                  {n.name}
                  {dirty.has(n.path) ? <span class="dirty" title="Edited, not saved"> ●</span> : null}
                </span>
                <CountLamps counts={counts.get(n.path)} compact />
              </button>
            );
          }}
        />
      )}
    </div>
  );
}
