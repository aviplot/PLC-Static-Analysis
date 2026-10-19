// Runs the smoke test in a real VS Code. VSCODE_EXECUTABLE picks an installed VS Code instead of downloading one;
// on a machine without a display, run under xvfb-run (CI). PLCA_E2E_KEEP keeps the workspace and the VS Code logs.
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { runTests } from "@vscode/test-electron";

async function main(): Promise<void> {
  const root = path.resolve(__dirname, "../../..");
  const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "plca-e2e-"));
  fs.cpSync(path.join(root, "test/fixtures/project"), workspace, { recursive: true });
  const userData = fs.mkdtempSync(path.join(os.tmpdir(), "plca-e2e-user-"));
  const extensions = fs.mkdtempSync(path.join(os.tmpdir(), "plca-e2e-ext-"));
  try {
    await runTests({
      vscodeExecutablePath: process.env.VSCODE_EXECUTABLE || undefined,
      extensionDevelopmentPath: root,
      extensionTestsPath: path.join(__dirname, "suite", "index.js"),
      launchArgs: [workspace, "--disable-extensions", "--user-data-dir", userData, "--extensions-dir", extensions],
    });
  } finally {
    if (process.env.PLCA_E2E_KEEP) {
      console.log(`kept: workspace ${workspace}, user data ${userData}`);
    } else {
      for (const dir of [workspace, userData, extensions]) {
        fs.rmSync(dir, { recursive: true, force: true });
      }
    }
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
