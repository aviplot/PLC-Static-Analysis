// The workbench server's HTTP API (WebServer on the server).

import type { RuleInfo } from './model.ts';

export interface ProjectInfo {
  id: string;
  name: string;
  files: number;
  skipped: number;
}

export interface SessionInfo {
  version: string;
  projects: ProjectInfo[];
  limits: { maxUploadBytes: number; maxExtractedBytes: number; maxEntries: number; projectsPerSession: number };
}

export interface FileInfo {
  path: string;
  size: number;
}

export interface CompareItem {
  status: 'new' | 'fixed' | 'unchanged';
  rule: string;
  severity: string;
  message: string;
  path: string;
  startLine: number;
  startCharacter: number;
  endLine: number;
  endCharacter: number;
}

export interface Comparison {
  base: string;
  head: string;
  added: number;
  fixed: number;
  unchanged: number;
  rules: { rule: string; added: number; fixed: number; unchanged: number }[];
  files: { path: string; change: 'added' | 'removed' | 'changed' | 'unchanged'; added: number; fixed: number }[];
  findings: CompareItem[];
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function check(r: Response): Promise<Response> {
  if (!r.ok) {
    const text = (await r.text()).trim();
    throw new ApiError(text || `${r.status} ${r.statusText}`, r.status);
  }
  return r;
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  return (await check(await fetch(path, { credentials: 'same-origin', ...init }))).json();
}

export const api = {
  session: () => json<SessionInfo>('/api/session'),
  rules: () => json<RuleInfo[]>('/api/rules'),
  about: async () => (await check(await fetch('/api/about'))).text(),
  tree: (id: string) => json<FileInfo[]>(`/api/projects/${id}/tree`),
  file: async (id: string, path: string) =>
    (await check(await fetch(`/api/projects/${id}/file?path=${encodeURIComponent(path)}`))).text(),
  remove: (id: string) => json<unknown>(`/api/projects/${id}`, { method: 'DELETE' }),
  compare: (base: string, head: string) =>
    json<Comparison>('/api/compare', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ base, head }),
    }),
  reportUrl: (id: string, format: 'html' | 'json' | 'sarif') => `/api/projects/${id}/report?format=${format}`,

  /** Uploads a zip; {@code progress} gets the sent fraction (0..1). XHR, as fetch reports no upload progress. */
  upload(name: string, zip: Blob, progress: (fraction: number) => void): Promise<ProjectInfo> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/projects');
      xhr.setRequestHeader('X-Plca-Name', encodeURIComponent(name));
      xhr.setRequestHeader('Content-Type', 'application/zip');
      xhr.upload.onprogress = (e) => e.lengthComputable && progress(e.loaded / e.total);
      xhr.onload = () => {
        if (xhr.status === 200) {
          resolve(JSON.parse(xhr.responseText));
        } else {
          reject(new ApiError(xhr.responseText.trim() || `upload failed (${xhr.status})`, xhr.status));
        }
      };
      xhr.onerror = () => reject(new ApiError('the upload did not reach the server', 0));
      xhr.send(zip);
    });
  },
};
