// plca for VS Code: starts the plca language server (Java) and connects it to the editor. All analysis happens in the
// server; this file only finds Java, builds the command line and adds two small UI helpers.

import { execFile } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import {
  ExecuteCommandRequest,
  LanguageClient,
  LanguageClientOptions,
  RevealOutputChannelOn,
  ServerOptions,
  TransportKind,
} from "vscode-languageclient/node";

const MIN_JAVA = 21;
const RULE_DOC = "plca.ruleDoc";

let client: LanguageClient | undefined;
let output: vscode.LogOutputChannel;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  output = vscode.window.createOutputChannel("plca", { log: true });
  context.subscriptions.push(
    output,
    vscode.commands.registerCommand("plca.restartServer", async () => {
      await stop();
      await start(context);
    }),
    vscode.commands.registerCommand("plca.showRuleDoc", (ruleId?: string) => showRuleDoc(ruleId)),
    vscode.languages.registerCodeActionsProvider(
      DOCUMENTS,
      new RuleDocActions(),
      { providedCodeActionKinds: [vscode.CodeActionKind.Empty] },
    ),
    vscode.workspace.onDidChangeConfiguration(async (e) => {
      if (e.affectsConfiguration("plca") && !e.affectsConfiguration("plca.trace.server")) {
        await stop();
        await start(context);
      }
    }),
  );
  await start(context);
}

export async function deactivate(): Promise<void> {
  await stop();
}

/** Files the server analyses: by language, and any XML (PLCopen exports are recognised by their namespace). */
const DOCUMENTS: { scheme: string; language?: string; pattern?: string }[] = [
  { scheme: "file", language: "iec-st" },
  { scheme: "file", language: "siemens-scl" },
  { scheme: "file", language: "twincat-xml" },
  { scheme: "file", language: "rockwell-l5x" },
  { scheme: "file", language: "rockwell-l5k" },
  { scheme: "file", pattern: "**/*.xml" },
];

/** Files whose changes on disk the server must see (closed files, plca.yaml, baselines). */
const WATCHED =
  "**/{*.st,*.ST,*.TcPOU,*.TcDUT,*.TcGVL,*.TcIO,*.TcTTO,*.scl,*.SCL,*.db,*.udt,*.L5X,*.l5x,*.L5K,*.l5k,*.xml,plca.yaml,*.json}";

async function start(context: vscode.ExtensionContext): Promise<void> {
  const settings = vscode.workspace.getConfiguration("plca");
  const server = await serverCommand(context, settings);
  if (!server) {
    return;
  }
  const serverOptions: ServerOptions = {
    command: server.command,
    args: server.args,
    transport: TransportKind.stdio,
    options: { env: { ...process.env } },
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: DOCUMENTS,
    outputChannel: output,
    revealOutputChannelOn: RevealOutputChannelOn.Never,
    synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher(WATCHED) },
    initializationOptions: {
      config: settings.get<string>("config") || undefined,
      threads: settings.get<number>("threads") || undefined,
      maxFileSizeMb: settings.get<number>("maxFileSizeMb"),
    },
  };
  client = new LanguageClient("plca", "plca", serverOptions, clientOptions);
  try {
    await client.start();
  } catch (e) {
    output.appendLine(`plca: the language server did not start: ${e}`);
    void vscode.window.showErrorMessage("plca: the language server did not start; see the plca output.");
  }
}

async function stop(): Promise<void> {
  const c = client;
  client = undefined;
  if (c) {
    await c.stop();
  }
}

interface Command {
  command: string;
  args: string[];
}

/** `plca.server.path` for development, else the bundled server on a suitable Java. */
async function serverCommand(
  context: vscode.ExtensionContext,
  settings: vscode.WorkspaceConfiguration,
): Promise<Command | undefined> {
  const launcher = settings.get<string>("server.path");
  if (launcher) {
    return { command: launcher, args: ["lsp"] }; // the client adds --stdio
  }
  const lib = context.asAbsolutePath(path.join("server", "lib"));
  if (!fs.existsSync(lib)) {
    void vscode.window.showErrorMessage(`plca: the bundled server is missing (${lib}); set plca.server.path.`);
    return undefined;
  }
  const java = javaExecutable(settings.get<string>("java.home"));
  const version = await javaVersion(java);
  if (version === undefined || version < MIN_JAVA) {
    const found = version === undefined ? `no Java at "${java}"` : `Java ${version} at "${java}"`;
    const choice = await vscode.window.showErrorMessage(
      `plca needs Java ${MIN_JAVA} or newer to run its language server; found ${found}.`,
      "Open Settings",
    );
    if (choice === "Open Settings") {
      void vscode.commands.executeCommand("workbench.action.openSettings", "plca.java.home");
    }
    return undefined;
  }
  return {
    command: java,
    args: ["-cp", path.join(lib, "*"), "dev.plca.cli.Main", "lsp"],
  };
}

function javaExecutable(home: string | undefined): string {
  const exe = process.platform === "win32" ? "java.exe" : "java";
  const base = home || process.env.JAVA_HOME;
  return base ? path.join(base, "bin", exe) : exe;
}

/** The Java feature version (21, 25, …), or undefined if it does not run. */
function javaVersion(java: string): Promise<number | undefined> {
  return new Promise((resolve) => {
    execFile(java, ["-version"], { timeout: 10_000 }, (error, _stdout, stderr) => {
      if (error) {
        resolve(undefined);
        return;
      }
      // `openjdk version "21.0.4"`, `java version "1.8.0_401"`
      const m = /version "(\d+)(?:\.(\d+))?/.exec(String(stderr));
      if (!m) {
        resolve(undefined);
        return;
      }
      const major = Number(m[1]);
      resolve(major === 1 && m[2] ? Number(m[2]) : major);
    });
  });
}

/** Opens the documentation of a rule (asks for the id when none is given) as a Markdown preview. */
async function showRuleDoc(ruleId?: string): Promise<void> {
  const c = client;
  if (!c) {
    return;
  }
  const id = ruleId ?? (await vscode.window.showInputBox({ prompt: "Rule id", placeHolder: "PLCA-Q003" }));
  if (!id) {
    return;
  }
  const markdown = await c.sendRequest(ExecuteCommandRequest.type, { command: RULE_DOC, arguments: [id] });
  if (typeof markdown !== "string") {
    void vscode.window.showWarningMessage(`plca: no rule ${id}`);
    return;
  }
  const doc = await vscode.workspace.openTextDocument({ language: "markdown", content: markdown });
  await vscode.commands.executeCommand("markdown.showPreview", doc.uri);
}

/** "Show documentation of PLCA-…" on every plca rule finding (not a fix: findings have no quick fixes yet). */
class RuleDocActions implements vscode.CodeActionProvider {
  provideCodeActions(
    _document: vscode.TextDocument,
    _range: vscode.Range,
    context: vscode.CodeActionContext,
  ): vscode.CodeAction[] {
    const ids = new Set<string>();
    for (const d of context.diagnostics) {
      const code = typeof d.code === "object" ? d.code.value : d.code;
      if (d.source === "plca" && typeof code === "string" && code.startsWith("PLCA-")) {
        ids.add(code);
      }
    }
    return [...ids].map((id) => {
      const action = new vscode.CodeAction(`Show documentation of ${id}`, vscode.CodeActionKind.Empty);
      action.command = { command: "plca.showRuleDoc", title: action.title, arguments: [id] };
      return action;
    });
  }
}
