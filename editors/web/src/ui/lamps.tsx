// Severity as indicator lamps (as on a control panel) and rule ids as cable-marker sleeves.

import type { Counts, Severity } from '../model.ts';

const NAMES: Record<Severity, string> = { error: 'Error', warning: 'Warning', info: 'Information', hint: 'Hint' };

export function Lamp({ severity, title }: { severity: Severity; title?: string }) {
  return <span class={`lamp lamp-${severity}`} role="img" aria-label={title ?? NAMES[severity]} title={title ?? NAMES[severity]} />;
}

/** Counts with their lamps; zero counts are left out. */
export function CountLamps({ counts, compact }: { counts: Counts | undefined; compact?: boolean }) {
  if (!counts) {
    return null;
  }
  const parts = (['error', 'warning', 'info'] as Severity[]).filter((s) => counts[s] > 0);
  if (parts.length === 0) {
    return null;
  }
  return (
    <span class={'counts' + (compact ? ' compact' : '')}>
      {parts.map((s) => (
        <span key={s} class="count" title={`${counts[s]} ${NAMES[s].toLowerCase()}${counts[s] === 1 ? '' : 's'}`}>
          <Lamp severity={s} />
          {counts[s]}
        </span>
      ))}
    </span>
  );
}

export function Marker({ id, title }: { id: string; title?: string }) {
  return (
    <span class="marker" title={title}>
      {id}
    </span>
  );
}
