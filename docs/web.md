# Web workbench

`plca web` serves a code review workbench in the browser: open a project export, read every finding, look at the code
with go-to-definition and hover, see Rockwell ladder and PLCopen LD/FBD as drawn, and compare two versions. It runs the
same analysis as `plca analyze` and the editor extension (language server over WebSocket). Plan and decisions:
[plans/M9.md](plans/M9.md).

Out of scope: compiling, downloading to a controller, online monitoring, hardware configuration, editing graphical
languages, user accounts.

## Running it

```sh
./gradlew :editors:web:bundle :plc-cli:installDist      # the browser app needs Node/npm to build, once
modules/plc-cli/build/install/plca/bin/plca web --open   # http://127.0.0.1:8080/
```

Without the bundle, `plca web` still starts and its page says how to build the app.

| Option | Default | |
|---|---|---|
| `--host` | `127.0.0.1` | interface to bind; anything but loopback requires a token |
| `--port` | `8080` | `0` picks a free port |
| `--token` | none on loopback, generated otherwise | clients open `/?token=…` once; the server then sets a cookie and drops the token from the URL |
| `--keep-uploads` | off | keep project directories after their session ends |
| `--work-dir` | `<tmp>/plca-web` | where uploads are unpacked |
| `--open` | off | open the default browser |

## Using it

1. **Open a project**: drop a folder or a zip on the page, or use "Choose a project folder" / "Choose a zip file". The
   browser keeps only the files plca reads (`.st`, `.TcPOU`/`.TcDUT`/`.TcGVL`/`.TcIO`/`.TcTTO`, `.scl`/`.db`/`.udt`,
   `.L5X`, `.xml`, `plca.yaml`, `.json` baselines) and uploads them as one zip; a zip is filtered by the server.
2. **Review**: files on the left (with finding counts), code in the middle, findings on the right. Filter findings by
   severity (the lamps), rule, category or words; group them by file or rule; "About this rule" shows the rule's
   documentation. Click a finding to go to it. In the code: F12 or Ctrl+click for the definition, Shift+F12 for
   references, hover for declarations; the Outline tab lists the file's POUs, variables and types.
3. **Ladder and diagrams** (L5X and PLCopen files): every routine or POU with a graphical body, rung by rung (pages of
   40 as you scroll). Elements with a finding are outlined in the finding's colour; hover shows the finding and the
   operand's declaration; click goes to the text.
4. **Edit to try a fix**: buffers are editable and analysed as you type. Edits are never written to the server;
   "Download edited files" gives a zip of the changed files (UTF-8).
5. **Compare versions**: upload the older and the newer export, pick both, compare. You get new, fixed and unchanged
   findings per rule and per file (matched by the same line-independent fingerprints as baselines), and a side-by-side
   diff with fixed findings marked on the old text and new ones on the new text.
6. **Reports**: HTML, SARIF and JSON of the project, as `plca analyze` writes them.

`plca.yaml` and its baseline in the uploaded folder apply, as for the CLI.

## Feature matrix

| | ST | TwinCAT XML | SCL | L5X | PLCopen XML |
|---|---|---|---|---|---|
| Findings, markers | yes | yes | yes | yes | yes |
| Highlighting | yes | XML + ST in CDATA | yes | XML + rung text and ST | XML + ST bodies |
| Definition, references, hover, outline | yes | yes | yes | as in the editor ([lsp.md](lsp.md)) | as in the editor |
| Drawings | — | — | — | ladder rungs | LD and FBD networks (SFC: not yet) |
| Compare | yes | yes | yes | yes | yes |

## Security and privacy

- **Local by default.** The server binds to `127.0.0.1` and accepts only `Host: localhost/127.0.0.1/[::1]` with its
  port, so a web page cannot reach it through DNS rebinding. Requests with a foreign `Origin` are refused, and a
  WebSocket must send its origin.
- **Shared use** (`--host 0.0.0.0`) requires a token. The server speaks plain HTTP: put it behind a TLS reverse proxy
  when it leaves the machine.
- **Sessions.** Each browser gets an HttpOnly, SameSite=Strict cookie. Projects belong to the session that uploaded
  them; another session cannot see, read or delete them, nor connect to their language server.
- **Uploads.** At most 200 MB per upload, 1 GB unpacked, 100,000 entries and 4 projects per session. Archives are
  streamed; entry names that would leave the project directory (`..`, absolute paths, drive letters, backslashes) are
  skipped, nothing is created but regular files, and a compression ratio over 100 stops the upload (zip bombs).
  Project directories are private to the server's user.
- **Deletion.** A project is deleted when its page has been closed for 2 minutes (a reload reconnects within that),
  after 30 minutes without activity, on "Close project", and when the server stops; directories left by a crashed
  server are removed at the next start. `--keep-uploads` keeps them.
- **Sandboxed analysis.** The language server reads only files inside the project; `plca.yaml` cannot point the
  baseline elsewhere, and server paths never appear in messages, diagnostics or reports (the project is
  `file:///project/`).
- **Nothing leaves.** All scripts, styles and fonts are served by plca itself; the Content-Security-Policy allows no
  other origin (`default-src 'self'`), and there is no telemetry. The end-to-end tests assert that the page makes no
  request elsewhere.

## Architecture

```
browser (editors/web: Preact, Monaco, own LSP client)
   │  HTTP: /api/…  (upload, tree, file, report, compare)        WebSocket /lsp/{project}: JSON-RPC, one message per frame
   ▼
plc-web  WebServer (Jetty 12) ── Guard (host, origin, token, session) ── Projects (unpack, lifecycle)
   │                                                                        │
   ├─ Analyses: Analyzer for reports and compare                            └─ LspSocket: one PlcaLanguageServer per
   ▼                                                                           connection, Uris.mapped, sandboxed
plc-analysis / plc-lsp / plc-report (LadderSvg, NetworkSvg)
```

- **Drawings are made on the server** (`plc-report`: `LadderSvg`, `NetworkSvg`, `Diagrams`) and requested through the
  language server (`plca/diagrams`, `plca/diagram`), so they always show the current text, and the HTML report can use
  them later. Every element carries `data-range` (LSP coordinates in the file), which the browser uses to place
  findings, ask for hovers and jump to the text.
- **Rungs** are laid out from the series/parallel ladder IR: a series left to right, a branch's legs stacked, outputs
  against the right rail as Studio 5000 draws them; contacts and coils as symbols, other instructions as boxes with
  their operands (labels for the common ones). **Networks** are drawn at the author's coordinates with the wires along
  their stored route; when the producer gave no sizes (CODESYS exports grid cells), the elements are laid out in
  columns by signal flow.
- **Highlighting** uses Monarch tokenizers generated by `editors/vscode/scripts/grammars.py` from the same keyword lists
  as the VS Code grammars.

## Testing

- `plc-web`: `WebServerTest` (uploads and limits, zip-slip, host/origin/token/session checks, files and reports without
  server paths, compare, LSP over a real WebSocket, sandboxed configuration, lifecycle), `ZipExtractorFuzzTest`
  (`JAZZER_FUZZ=1` to fuzz).
- `plc-report`: `DiagramsTest` (golden SVG of every Rockwell and PLCopen fixture, well-formed).
- `./gradlew :plc-devtools:renderCheck [--args=--sample=build/render-sample.html]`: draws every rung and network of the
  local corpus.
- `editors/web`: `./gradlew :editors:web:unitTest` (filters, grouping, URIs, tree), `:editors:web:e2eTest` (Playwright
  in the installed Google Chrome against `plca web`: review, editing, ladder, compare, clean-up, no foreign requests).
  `scripts/ci.sh` runs the unit tests and the bundle when npm is installed, the end-to-end tests with `PLCA_E2E=1`.

## Performance (laptop, local server)

| | |
|---|---|
| Generated project, 203 files, 18k lines: upload to full analysis shown | 0.95 s |
| Generated project, 2,003 files, 184k lines: upload to full analysis shown | 1.4 s |
| 43 MB Rockwell controller export: upload to first routine drawn | 3.8 s (the server's first parse of the file) |
| Same file: switching routines, next page of rungs | < 10 ms |
| Corpus: 3.08M rungs and networks drawn | p50 0.03 ms, p99 0.3 ms per part |

The browser app is 4.6 MB (Monaco 4 MB of it), served once and cached.
