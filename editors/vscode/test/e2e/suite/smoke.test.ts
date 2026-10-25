// Smoke test of the packaged extension: the server starts on the bundled jars, publishes a rule finding, and answers
// definition, hover and outline requests.
import * as assert from "node:assert";
import * as vscode from "vscode";

const folder = () => vscode.workspace.workspaceFolders![0].uri;

async function until<T>(what: string, f: () => T | undefined | Promise<T | undefined>, ms = 90_000): Promise<T> {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = await f();
    if (v !== undefined) {
      return v;
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`timed out waiting for ${what}`);
}

suite("plca", () => {
  test("diagnostics, definition, hover, outline", async () => {
    const main = vscode.Uri.joinPath(folder(), "MAIN.st");
    const doc = await vscode.workspace.openTextDocument(main);
    await vscode.window.showTextDocument(doc);
    assert.strictEqual(doc.languageId, "iec-st");

    // PLCA-Q003: equality comparison of REAL values
    const q003 = await until("PLCA-Q003 on MAIN.st", () =>
      vscode.languages
        .getDiagnostics(main)
        .find((d) => d.source === "plca" && (typeof d.code === "object" ? d.code.value : d.code) === "PLCA-Q003"),
    );
    assert.strictEqual(q003.range.start.line, 7);

    // bRun in `fbPump(bRun := TRUE)` → its declaration in FB_Pump.st
    const defs = await vscode.commands.executeCommand<(vscode.Location | vscode.LocationLink)[]>(
      "vscode.executeDefinitionProvider",
      main,
      new vscode.Position(6, 8),
    );
    assert.strictEqual(defs.length, 1);
    const def = defs[0];
    const uri = "uri" in def ? def.uri : def.targetUri;
    const range = "range" in def ? def.range : def.targetRange;
    assert.ok(uri.path.endsWith("/FB_Pump.st"), uri.toString());
    assert.strictEqual(range.start.line, 3);

    const hovers = await vscode.commands.executeCommand<vscode.Hover[]>(
      "vscode.executeHoverProvider",
      main,
      new vscode.Position(6, 8),
    );
    const text = hovers
      .flatMap((h) => h.contents)
      .map((c) => (typeof c === "string" ? c : c.value))
      .join("\n");
    assert.ok(text.includes("VAR_INPUT bRun : BOOL"), text);
    assert.ok(text.includes("run command from MAIN"), text);

    const symbols = await vscode.commands.executeCommand<vscode.DocumentSymbol[]>(
      "vscode.executeDocumentSymbolProvider",
      main,
    );
    assert.deepStrictEqual(
      symbols.map((s) => s.name),
      ["MAIN"],
    );
    assert.deepStrictEqual(
      symbols[0].children.map((s) => s.name),
      ["fbPump", "rLevel", "bAlarm"],
    );

    // an edit that breaks the syntax is reported without saving
    const editor = vscode.window.activeTextEditor!;
    await editor.edit((e) => e.replace(new vscode.Range(7, 10, 7, 16), "rLevel +"));
    await until("a syntax error after the edit", () =>
      vscode.languages
        .getDiagnostics(main)
        .find((d) => (typeof d.code === "object" ? d.code.value : d.code) === "PLC-SYN-001"),
    );
  });

  test("PLCopen XML by its namespace, TwinCAT task objects", async () => {
    const tank = vscode.Uri.joinPath(folder(), "Tank.xml");
    await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(tank));
    await until("Tank.xml as plcopen-xml", () =>
      vscode.workspace.textDocuments.find((d) => d.uri.toString() === tank.toString() && d.languageId === "plcopen-xml"),
    );
    const q003 = await until("PLCA-Q003 on Tank.xml", () =>
      vscode.languages
        .getDiagnostics(tank)
        .find((d) => d.source === "plca" && (typeof d.code === "object" ? d.code.value : d.code) === "PLCA-Q003"),
    );
    assert.strictEqual(q003.range.start.line, 16);

    const task = await vscode.workspace.openTextDocument(vscode.Uri.joinPath(folder(), "PlcTask.TcTTO"));
    assert.strictEqual(task.languageId, "twincat-xml");
  });
});
