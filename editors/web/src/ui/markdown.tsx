// A small Markdown renderer for rule documentation and hovers: headings, paragraphs, lists, fenced code, inline code,
// bold and italics. It builds elements, never HTML strings, so text from projects cannot inject markup.

import type { ComponentChildren } from 'preact';

function inline(text: string): ComponentChildren[] {
  const out: ComponentChildren[] = [];
  const re = /(`[^`]+`|\*\*[^*]+\*\*|\*[^*\s][^*]*\*|_[^_\s][^_]*_)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    if (m.index > last) {
      out.push(text.slice(last, m.index));
    }
    const t = m[0];
    if (t.startsWith('`')) {
      out.push(<code>{t.slice(1, -1)}</code>);
    } else if (t.startsWith('**')) {
      out.push(<strong>{t.slice(2, -2)}</strong>);
    } else {
      out.push(<em>{t.slice(1, -1)}</em>);
    }
    last = m.index + t.length;
  }
  if (last < text.length) {
    out.push(text.slice(last));
  }
  return out;
}

export function Markdown({ text }: { text: string }) {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const blocks: ComponentChildren[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith('```')) {
      const code: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        code.push(lines[i++]);
      }
      i++;
      blocks.push(<pre>{code.join('\n')}</pre>);
    } else if (/^#{1,6}\s/.test(line)) {
      const level = line.match(/^#+/)![0].length;
      const content = inline(line.replace(/^#+\s*/, ''));
      blocks.push(level <= 2 ? <h3>{content}</h3> : <h4>{content}</h4>);
      i++;
    } else if (/^\s*[-*]\s/.test(line)) {
      const items: ComponentChildren[] = [];
      while (i < lines.length && /^\s*[-*]\s/.test(lines[i])) {
        items.push(<li>{inline(lines[i].replace(/^\s*[-*]\s/, ''))}</li>);
        i++;
      }
      blocks.push(<ul>{items}</ul>);
    } else if (line.trim() === '' || /^---+$/.test(line.trim())) {
      i++;
    } else {
      const para: string[] = [];
      while (i < lines.length && lines[i].trim() !== '' && !/^(#|```|\s*[-*]\s)/.test(lines[i])) {
        para.push(lines[i++]);
      }
      blocks.push(<p>{inline(para.join(' '))}</p>);
    }
  }
  return <div class="md">{blocks}</div>;
}
