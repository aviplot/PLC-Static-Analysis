// The workbench in a real browser against `plca web`: upload, findings, navigation, editing, ladder, compare,
// privacy (no request leaves the server) and clean-up of uploads.

import { type Page, expect, test } from '@playwright/test';
import { unzipSync, zipSync } from 'fflate';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const PROJECTS = new URL('../../../../modules/plc-semantic/src/test/resources/projects/', import.meta.url).pathname;

/** A zip of test projects, as a folder upload would send them; {@code edit} changes files on the way. */
function zipProjects(names: string[], edit: (path: string, text: string) => string | null = (_, t) => t): Buffer {
  const files: Record<string, Uint8Array> = {};
  const walk = (dir: string, prefix: string) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) {
        walk(full, prefix + name + '/');
      } else {
        const text = edit(prefix + name, readFileSync(full, 'utf8'));
        if (text !== null) {
          files[prefix + name] = new TextEncoder().encode(text);
        }
      }
    }
  };
  names.forEach((n) => walk(join(PROJECTS, n), n + '/'));
  return Buffer.from(zipSync(files));
}

/** Every request a page makes that does not go to the workbench's own origin. */
function watchRequests(page: Page): string[] {
  const foreign: string[] = [];
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (!['blob:', 'data:'].includes(u.protocol) && u.host !== new URL(page.url() || 'http://127.0.0.1').host) {
      foreign.push(r.url());
    }
  });
  return foreign;
}

async function upload(page: Page, name: string, zip: Buffer) {
  await page.locator('input[accept*=zip]').first().setInputFiles({ name: `${name}.zip`, mimeType: 'application/zip', buffer: zip });
  await expect(page.locator('.project-pick select')).toHaveValue(/.+/);
  await expect(page.locator('.project-pick option:checked')).toHaveText(name);
}

test('review: findings, navigation, definition, outline', async ({ page }) => {
  await page.goto('/');
  const foreign = watchRequests(page);
  const frames: string[] = [];
  page.on('websocket', (ws) => ws.on('framereceived', (f) => frames.push(String(f.payload))));
  await expect(page.getByRole('heading', { name: 'Review a PLC project' })).toBeVisible();
  // the folder button opens a folder picker, not a file picker
  expect(await page.locator('input[type=file]:not([accept])').first().evaluate((i: HTMLInputElement) => i.webkitdirectory)).toBe(true);
  await upload(page, 'plant', zipProjects(['plant', 'rockwell_line']));

  // the analysis arrives: the recursion error of functions.st among them
  const findings = page.getByRole('region', { name: 'Findings' });
  // the server reported its load progress, from begin to end, and the page dropped it at the end
  await expect.poll(() => frames.some((f) => f.includes('$/progress') && f.includes('"end"')), { timeout: 30_000 }).toBe(true);
  expect(frames.some((f) => f.includes('$/progress') && f.includes('Loading the project'))).toBe(true);
  await expect(page.locator('footer progress')).toHaveCount(0);
  await expect(findings.getByText(/Recursive call cycle/)).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.status')).toContainText('Analysis up to date', { timeout: 30_000 });

  // only errors
  await findings.locator('.severity-toggles').getByRole('button', { name: /Warnings/ }).click();
  await findings.locator('.severity-toggles').getByRole('button', { name: /Information/ }).click();
  await expect(findings.locator('.finding')).toHaveCount(1);
  await findings.locator('.severity-toggles').getByRole('button', { name: /Warnings/ }).click();
  await findings.locator('.severity-toggles').getByRole('button', { name: /Information/ }).click();

  // a finding opens its file with the range selected
  await findings.getByText(/Recursive call cycle/).click();
  await expect(page.locator('.tab.on')).toContainText('functions.st');
  await expect(page.locator('.code .view-lines')).toContainText('F_Scale');
  await expect(page.locator('.code .squiggly-error').first()).toBeVisible();

  // go to definition (F12) from MAIN.st to FB_Motor.st
  await page.locator('.tree').getByRole('button', { name: /^MAIN\.st/ }).click();
  await expect(page.locator('.tab.on')).toContainText('MAIN.st');
  // line 3 is "    fbMotor : FB_Motor;": the cursor into the type name
  await page.locator('.code .view-lines').click();
  await page.keyboard.press('Control+Home');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('End');
  for (let i = 0; i < 3; i++) {
    await page.keyboard.press('ArrowLeft');
  }
  await page.keyboard.press('F12');
  await expect(page.locator('.tab.on')).toContainText('FB_Motor.st', { timeout: 15_000 });

  // the outline of the open file
  await page.getByRole('tab', { name: 'Outline' }).click();
  await expect(page.locator('.outline')).toContainText('FB_Motor');
  expect(foreign).toEqual([]);
});

test('editing: live diagnostics and download of edited files', async ({ page }) => {
  await page.goto('/');
  await upload(page, 'edit', zipProjects(['plant']));
  await page.locator('.tree').getByRole('button', { name: /^GVL_Main\.st/ }).click();
  await expect(page.locator('.code .view-lines')).toContainText('VAR_GLOBAL');
  await page.locator('.code .view-line').first().click();
  await page.keyboard.press('End');
  await page.keyboard.type(' garbage ((');
  await expect(page.getByRole('region', { name: 'Findings' }).getByText(/GVL_Main\.st/)).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('.code .squiggly-error').first()).toBeVisible({ timeout: 20_000 });
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download 1 edited file' }).click();
  expect((await download).suggestedFilename()).toBe('edit-edited.zip');
});

test('download: edited files keep their encoding and line endings', async ({ page }) => {
  await page.goto('/');
  // Windows-1252 with CRLF, as older CODESYS exports: "Größe" is 47 72 F6 DF 65
  const text = 'PROGRAM Legacy (* Gr\u00f6\u00dfe *)\r\nVAR\r\n  x : INT;\r\nEND_VAR\r\nx := 1;\r\nEND_PROGRAM\r\n';
  const cp1252 = Uint8Array.from([...text].map((c) => c.charCodeAt(0)));
  await upload(page, 'legacy', Buffer.from(zipSync({ 'legacy/Legacy.st': cp1252 })));
  await page.locator('.tree').getByRole('button', { name: /^Legacy\.st/ }).click();
  await expect(page.locator('.code .view-lines')).toContainText('PROGRAM Legacy');
  await page.locator('.code .view-line').nth(4).click();
  await page.keyboard.press('End');
  await page.keyboard.type(' // \u00e4');

  const download = async () => {
    const d = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download 1 edited file' }).click();
    return unzipSync(readFileSync(await (await d).path()))['legacy/Legacy.st'];
  };
  const kept = await download();
  const expected = text.replace('x := 1;', 'x := 1; // \u00e4');
  expect([...kept]).toEqual([...expected].map((c) => c.charCodeAt(0)));
  await expect(page.locator('.toast')).toHaveCount(0);

  // an arrow does not exist in Windows-1252: UTF-8 with BOM, and the page says so
  await page.locator('.code .view-line').nth(4).click();
  await page.keyboard.press('End');
  await page.keyboard.type(' \u2192');
  const utf8 = await download();
  expect([...utf8.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
  expect(new TextDecoder().decode(utf8.slice(3))).toBe(expected.replace('\u00e4', '\u00e4 \u2192'));
  await expect(page.locator('.toast')).toContainText('legacy/Legacy.st (\u2192 not in windows-1252)');
});

test('ladder: rungs drawn, findings on the rung, hover and click to text', async ({ page }) => {
  await page.goto('/');
  await upload(page, 'line', zipProjects(['rockwell_line']));
  await page.locator('.tree').getByRole('button', { name: /^Line1\.L5X/ }).click();
  await page.getByRole('button', { name: 'Ladder and diagrams' }).click();
  // finding counts per routine; "only with findings" hides the clean ones
  const list = page.locator('.diagram-list');
  await expect(list.getByRole('button', { name: /MainProgram\/Alarms/ }).locator('.counts')).toBeVisible({ timeout: 30_000 });
  const all = await list.locator('button').count();
  await list.getByLabel('Only with findings').check();
  const withFindings = await list.locator('button').count();
  expect(withFindings).toBeGreaterThan(0);
  expect(withFindings).toBeLessThan(all);
  await list.getByLabel('Only with findings').uncheck();
  await list.getByRole('button', { name: /MainProgram\/Alarms/ }).click();
  await expect(page.locator('.part svg')).toHaveCount(2);
  await expect(page.locator('.part .has-finding').first()).toBeVisible({ timeout: 30_000 });
  await page.locator('.part text[data-range]').first().hover();
  await expect(page.locator('.tip')).toContainText('PLCA-P002');
  await page.locator('.part text[data-range]').first().click();
  await expect(page.getByRole('button', { name: 'Text', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.code .view-lines')).toContainText('Rack1:1:I.Data.3');
});

test('compare: new and fixed findings between two versions', async ({ page }) => {
  await page.goto('/');
  await upload(page, 'v1', zipProjects(['plant']));
  await upload(
    page,
    'v2',
    zipProjects(['plant'], (path, text) =>
      path.endsWith('functions.st') ? null : path.endsWith('MAIN.st') ? text.replace(/^VAR$/m, 'VAR\n    iNeverUsed : INT;') : text,
    ),
  );
  await page.getByRole('button', { name: 'Compare versions' }).click();
  const head = page.locator('.compare-head');
  await head.locator('select').nth(0).selectOption({ label: 'v1' });
  await head.locator('select').nth(1).selectOption({ label: 'v2' });
  await head.getByRole('button', { name: 'Compare', exact: true }).click();
  await expect(page.locator('.tally')).toContainText('1 new', { timeout: 60_000 });
  await expect(page.locator('.tally')).toContainText('1 fixed');
  await expect(page.locator('.file-delta')).toContainText('plant/functions.st');
  await expect(page.locator('.diff-findings')).toContainText("Variable 'iNeverUsed' is declared but never used");
});

test('uploads are deleted when the project is closed or the page goes away', async ({ browser }) => {
  const work = process.env.PLCA_E2E_WORK!;
  const projectDirs = () => readdirSync(work).filter((d) => d.startsWith('p-'));
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/');
  await upload(page, 'gone', zipProjects(['plant']));
  const id = (await page.locator('.project-pick select').inputValue()) as string;
  expect(existsSync(join(work, 'p-' + id))).toBe(true);
  await page.getByRole('button', { name: 'Close project' }).click();
  await expect.poll(() => existsSync(join(work, 'p-' + id))).toBe(false);

  await upload(page, 'gone2', zipProjects(['plant']));
  const id2 = (await page.locator('.project-pick select').inputValue()) as string;
  await expect(page.locator('.status')).toContainText('Analysis', { timeout: 30_000 });
  await context.close(); // the page goes away: its WebSocket closes, the 2 s grace passes, the sweep deletes it
  await expect.poll(() => existsSync(join(work, 'p-' + id2)), { timeout: 15_000 }).toBe(false);
  expect(projectDirs()).not.toContain('p-' + id2);
});
