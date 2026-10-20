// A windowed list: renders only the rows in view. Rows have known heights (per kind), so offsets are prefix sums.

import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';

interface Props<T> {
  items: T[];
  height: (item: T) => number;
  render: (item: T, index: number) => ComponentChildren;
  /** Scrolls this index into view when it changes. */
  reveal?: number;
  class?: string;
  label?: string;
}

export function VirtualList<T>({ items, height, render, reveal, class: cls, label }: Props<T>) {
  const ref = useRef<HTMLDivElement>(null);
  const [scroll, setScroll] = useState(0);
  const [viewport, setViewport] = useState(600);
  const offsets = useMemo(() => {
    const o = new Array<number>(items.length + 1);
    o[0] = 0;
    for (let i = 0; i < items.length; i++) {
      o[i + 1] = o[i] + height(items[i]);
    }
    return o;
  }, [items, height]);

  useEffect(() => {
    const el = ref.current!;
    const ro = new ResizeObserver(() => setViewport(el.clientHeight));
    ro.observe(el);
    setViewport(el.clientHeight);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (el && reveal !== undefined && reveal >= 0 && reveal < items.length) {
      const top = offsets[reveal];
      const bottom = offsets[reveal + 1];
      if (top < el.scrollTop || bottom > el.scrollTop + el.clientHeight) {
        el.scrollTop = Math.max(0, top - el.clientHeight / 3);
      }
    }
  }, [reveal]);

  // first row whose bottom is below the top of the view (binary search), a few rows of slack each side
  let lo = 0;
  let hi = items.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (offsets[mid + 1] <= scroll) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  const first = Math.max(0, lo - 8);
  let last = lo;
  while (last < items.length && offsets[last] < scroll + viewport) {
    last++;
  }
  last = Math.min(items.length, last + 8);
  const rows = [];
  for (let i = first; i < last; i++) {
    rows.push(
      <div key={i} class="vrow" style={{ top: `${offsets[i]}px`, height: `${offsets[i + 1] - offsets[i]}px` }}>
        {render(items[i], i)}
      </div>,
    );
  }
  return (
    <div
      ref={ref}
      class={'vlist ' + (cls ?? '')}
      role="list"
      aria-label={label}
      onScroll={(e) => setScroll((e.currentTarget as HTMLDivElement).scrollTop)}
    >
      <div class="vspace" style={{ height: `${offsets[items.length]}px` }}>
        {rows}
      </div>
    </div>
  );
}
