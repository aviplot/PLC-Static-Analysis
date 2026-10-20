import { defineConfig } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// plca web from ./gradlew :plc-cli:installDist (the e2eTest task builds it), on its own port and work directory
const port = Number(process.env.PLCA_E2E_PORT ?? 18765);
const work = process.env.PLCA_E2E_WORK ?? mkdtempSync(join(tmpdir(), 'plca-e2e-'));
process.env.PLCA_E2E_WORK = work;
const plca = new URL('../../../../modules/plc-cli/build/install/plca/bin/plca', import.meta.url).pathname;

export default defineConfig({
  testDir: '.',
  timeout: 90_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    // the installed Google Chrome: no browser download (set PLCA_E2E_CHANNEL= to use Playwright's own build)
    channel: process.env.PLCA_E2E_CHANNEL ?? 'chrome',
    viewport: { width: 1440, height: 900 },
  },
  webServer: {
    command: `${plca} web --port ${port} --work-dir ${work} --grace-seconds 2`,
    url: `http://127.0.0.1:${port}/api/session`,
    reuseExistingServer: false,
    timeout: 60_000,
    stdout: 'pipe',
  },
});
