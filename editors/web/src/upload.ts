// Turns what the user drops or picks (a zip, a folder, loose files) into one zip for the server. Folders are filtered
// here to what the analyzer reads, so a project folder with build output does not upload hundreds of megabytes.

import { zip, type Zippable } from 'fflate';
import { uploadable } from './model.ts';

export interface Picked {
  /** A project name for the list: the folder's or zip's name. */
  name: string;
  /** The zip to send. */
  zip: Blob;
  /** Files kept and left out (folders only; a zip is filtered by the server). */
  kept: number;
  skipped: number;
}

/** The FileList of a folder input (webkitdirectory) or a multi-file input. */
export async function fromFileList(files: FileList): Promise<Picked> {
  const list = Array.from(files);
  if (list.length === 1 && /\.zip$/i.test(list[0].name)) {
    return { name: list[0].name.replace(/\.zip$/i, ''), zip: list[0], kept: 0, skipped: 0 };
  }
  const entries = list.map((f) => ({ path: (f as any).webkitRelativePath || f.name, file: f }));
  return pack(entries);
}

/** A drop: a zip, folders and files (walked through the entries API). */
export async function fromDrop(items: DataTransferItemList): Promise<Picked> {
  const roots: FileSystemEntry[] = [];
  for (const item of Array.from(items)) {
    const e = item.webkitGetAsEntry?.();
    if (e) {
      roots.push(e);
    }
  }
  if (roots.length === 1 && roots[0].isFile && /\.zip$/i.test(roots[0].name)) {
    const f = await fileOf(roots[0] as FileSystemFileEntry);
    return { name: f.name.replace(/\.zip$/i, ''), zip: f, kept: 0, skipped: 0 };
  }
  const entries: { path: string; file: File }[] = [];
  for (const r of roots) {
    await walk(r, '', entries);
  }
  return pack(entries);
}

async function walk(entry: FileSystemEntry, prefix: string, out: { path: string; file: File }[]): Promise<void> {
  const path = prefix + entry.name;
  if (entry.isFile) {
    out.push({ path, file: await fileOf(entry as FileSystemFileEntry) });
    return;
  }
  if (entry.name.startsWith('.') || entry.name === 'node_modules') {
    return; // .git, .vs, …
  }
  const reader = (entry as FileSystemDirectoryEntry).createReader();
  for (;;) {
    // readEntries returns at most 100 entries per call
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
    if (batch.length === 0) {
      break;
    }
    for (const e of batch) {
      await walk(e, path + '/', out);
    }
  }
}

function fileOf(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject));
}

async function pack(entries: { path: string; file: File }[]): Promise<Picked> {
  const kept = entries.filter((e) => uploadable(e.path));
  if (kept.length === 0) {
    throw new Error(
      'No PLC sources found. The workbench reads .st, TwinCAT (.TcPOU, .TcDUT, .TcGVL), Siemens (.scl, .db, .udt), Rockwell .L5X/.L5K and PLCopen .xml files.',
    );
  }
  const files: Zippable = {};
  for (const e of kept) {
    files[e.path] = [new Uint8Array(await e.file.arrayBuffer()), { level: 6 }];
  }
  const data = await new Promise<Uint8Array>((resolve, reject) =>
    zip(files, { level: 6 }, (err, out) => (err ? reject(err) : resolve(out))),
  );
  const top = kept[0].path.includes('/') ? kept[0].path.slice(0, kept[0].path.indexOf('/')) : 'project';
  const name = kept.every((e) => e.path.startsWith(top + '/')) ? top : 'project';
  return {
    name,
    zip: new Blob([data as BlobPart], { type: 'application/zip' }),
    kept: kept.length,
    skipped: entries.length - kept.length,
  };
}

/** Saves a blob as a file through a temporary link. */
export function save(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
