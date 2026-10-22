# Installing the VS Code extension

The extension (`editors/vscode/`) is not on the Marketplace yet. You build a `plca.vsix` file from this repository
and install it into VS Code. The file contains the extension and the plca language server jars. What the extension
does, and how the server works: [lsp.md](lsp.md).

## Requirements

| | Needed for | Check |
|---|---|---|
| Java 21 or newer | building, and running the language server | `java -version` |
| Node.js 20+ and npm | building the `.vsix` only | `node -v`, `npm -v` |
| VS Code 1.100 or newer | running the extension | `code --version` |

## 1. Build the `.vsix`

From the repository root:

```sh
./gradlew :editors:vscode:package
```

This builds the server (`:plc-cli:installDist`), copies its jars to `editors/vscode/server/lib/`, runs `npm ci` and
packages everything into **`editors/vscode/plca.vsix`**. The task is not part of `./gradlew build`, so the normal
build never needs Node. The `.vsix` is git-ignored; build it again after pulling changes.

## 2. Install it

Command line:

```sh
code --install-extension editors/vscode/plca.vsix
```

Or in VS Code: open the Extensions view (Ctrl+Shift+X), click the `…` menu at the top of the view, choose
**Install from VSIX…** and pick `editors/vscode/plca.vsix`.

Reload the window if VS Code asks (Command Palette: **Developer: Reload Window**).

To copy the extension to another machine, copy only `plca.vsix` and install it there the same way. That machine
needs Java 21+ but not Node.

## 3. Check that it works

1. Open a folder with PLC sources (`.st`, `.TcPOU`, `.scl`, `.L5X`, PLCopen `.xml`, …). The extension starts when
   the folder contains such a file.
2. Open a file: keywords are highlighted and findings appear in the **Problems** panel (Ctrl+Shift+M).
3. **View → Output**, choose **plca** in the drop-down: the server log. Errors starting the server show up here.

The Command Palette (Ctrl+Shift+P) has **plca: Analyze workspace**, **plca: Show rule documentation** and
**plca: Restart language server**.

## Java

The server runs on the first Java it finds:

1. the setting `plca.java.home` (the JDK/JRE folder, not the `java` binary),
2. the `JAVA_HOME` environment variable,
3. `java` on the `PATH`.

If that Java is missing or older than 21, the extension shows an error with a button that opens the setting.

## Settings

Rule configuration comes from `plca.yaml` in the workspace folder, as for `plca analyze`
([configuration.md](configuration.md)); `plca.config` points elsewhere. All settings are listed in
[editors/vscode/README.md](../editors/vscode/README.md#settings).

## Update and uninstall

- **Update**: build the `.vsix` again and run the same `code --install-extension` command; it replaces the installed
  version. Then reload the window.
- **Uninstall**: `code --uninstall-extension AvrahamPlotnitzky.plca`, or the gear icon on the extension in the Extensions view.

## Development: use a server you are working on

Repackaging after every server change is slow. Instead build the CLI and point the installed extension at it:

```sh
./gradlew :plc-cli:installDist
```

Then set `plca.server.path` to `<repo>/modules/plc-cli/build/install/plca/bin/plca` (user or workspace settings) and
run **plca: Restart language server** after each rebuild. Set `plca.trace.server` to `messages` or `verbose` to see
the LSP traffic in the plca output.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "plca needs Java 21 or newer …" | Install Java 21+, or set `plca.java.home` to it. |
| "the bundled server is missing" | The `.vsix` was built without `copyServer`; build it with the Gradle task above, not with `npm run package` alone. |
| No highlighting, no findings | Check the language mode in the status bar (e.g. *Structured Text (IEC 61131-3)*), and the plca output for errors. |
| `code: command not found` | In VS Code: Command Palette → **Shell Command: Install 'code' command in PATH** (macOS), or use **Install from VSIX…**. |
| Findings of a large L5X only appear on save | Files over `plca.maxFileSizeMb` (20 MB) are analysed from disk, not from the editor buffer. |
