# Language server and VS Code extension

`plca lsp` is a language server (LSP 3.17, stdio) over the same pipeline as `plca analyze`: frontends, semantic model,
rules, `plca.yaml`, suppressions and baseline. `editors/vscode/` is a thin VS Code client for it. Plan and design
decisions: [plans/M8.md](plans/M8.md).

## Using it

```sh
./gradlew :editors:vscode:package          # editors/vscode/plca.vsix, server jars included (needs Node/npm)
code --install-extension editors/vscode/plca.vsix
```

Step by step, with checks, updating and troubleshooting: [vscode.md](vscode.md).

The server needs Java 21+: the setting `plca.java.home`, else `JAVA_HOME`, else `java` on the PATH. Other editors start
`plca lsp` (from `./gradlew :plc-cli:installDist`) as a stdio server; `--stdio` is accepted.

`initializationOptions` (all optional): `config` (path of `plca.yaml`, relative to the root), `threads` (project
tier), `fastDelayMs` (100), `fullDelayMs` (750), `maxFileSizeMb` (20). The root is the first workspace folder.

## Features per language

| | ST (`.st`) | TwinCAT XML | SCL (`.scl/.db/.udt`) | L5X | PLCopen XML |
|---|---|---|---|---|---|
| Diagnostics (syntax, model, rules) | yes | yes, on the XML lines | yes | yes, on rung/ST text and tags | yes |
| Definition, references | yes | yes, inside CDATA | yes (`"DB".member`, `#local`) | tags, UDT members, AOIs (also from the rung mnemonic), routines | ST bodies; graphical bodies through their element spans |
| Hover | declaration, owner, comment | same | same | name and type (L5X has no declaration text) | name and type |
| Outline | POUs, members, variables, types | same | blocks, DB members | UDTs, AOIs, controller tags, programs, routines, tasks | types, POUs, variables, configuration |
| Highlighting (TextMate) | yes | XML + ST in `Declaration`/`ST` CDATA | yes | XML + rung text in `Text`, ST in `Line` | VS Code's XML |

Workspace symbols: POUs, types and global variables of every model, matched by subsequence (`fbm` finds `FB_Motor`).
Requests beyond LSP: `plca/diagrams {uri}` lists a file's ladder routines and LD/FBD bodies with each one's text range,
`plca/diagram {uri, name, from | at, count}` draws a page of their rungs or networks as SVG, from part `from` or from
the part holding position `at` (used by the web workbench, [web.md](web.md)).
**Loading.** When the client supports work-done progress (`window.workDoneProgress`; VS Code shows it in the status
bar), loading a workspace reports "Reading files: n of N", "Building the semantic model" and "Checking the rules", and
ends with the number of files analysed. Drawings do not wait for the load: while files are parsed, `plca/diagrams` and
`plca/diagram` answer from a quick read of that one file (an L5X read parses the rungs of the requested routine only,
under half a second for a 43 MB export once warm), and while the first model is built, from the parsed units.
Commands: `plca.analyzeWorkspace` (re-read everything from disk), `plca.ruleDoc` (server: a rule's Markdown), and in
VS Code "Show documentation of PLCA-…" on every finding.

## How an edit is processed

1. The client sends the full text (full sync). The analysis thread parses that file only, with a frontend per thread,
   into a new `SourceRegistry` derived from the previous one (`without(path)`): ids are never reused, so older
   snapshots stay valid for queries in flight.
2. **Fast tier**, 100 ms after the last edit: a snapshot (`Workspace.snapshot()`) rebuilds the semantic model of the
   edited file's model group (other groups keep theirs), and the POU rules run on the POUs of the edited files. The
   file's parse, model and rule diagnostics are published with the document version.
   The model is rebuilt **incrementally** (M10, `SemanticBuilder.buildForEditing`): each unit's declarations,
   bindings and dependencies are kept, and an edit declares again only the edited file and the files whose
   declarations use its symbols (an FB holding an instance of it, a type built on it, transitively), and binds again
   those plus the files whose bodies use them. Everything else, including its side-table entries, is taken from the
   previous model. A full build runs instead when the result could differ: the edit changes names other files see
   (a POU, type, variable list or global variable added, removed or renamed), the file shares declarations with
   others (a name declared twice, a variable list split over files, data blocks, a configuration), the group is a
   Rockwell controller, or more than a third of the group is affected. Either way the model equals a full build;
   `-Dplca.model.verify=true` checks that on every edit (slow), `./gradlew :plc-devtools:modelCheck` on corpus
   projects.
3. **Project tier**, 750 ms after the last edit, on save and after loading: all rules on the snapshot, on their own
   thread; diagnostics of every file whose set changed are published. Project-rule findings of an edited file are
   withdrawn by the fast tier and come back here.
4. Every edit increments a generation. Runs check it between POUs and rules, and results of an older generation are
   never published.

Queries (definition, hover, …) run on the analysis thread after every edit received before them, on the current
snapshot. Node and reference indexes are built lazily per snapshot; indexes of unchanged units are reused.

Files changed on disk (watched by the client) are re-read unless open in an editor; closing a buffer re-reads the
file. Changes to `plca.yaml` or the baseline reload the configuration; a broken `plca.yaml` is reported on its line
and the defaults apply.

## Performance

JMH `EditCycleBenchmark` (`./gradlew :plc-devtools:jmh -Pjmh.args=EditCycleBenchmark`), one thread, with the
incremental model (M10; M8's full rebuild in brackets):

| Project | Fast tier p50 / p95 | Project tier p50 / p95 |
|---|---|---|
| generated, 200 FBs (≈ 24k lines) | 2.4 / 2.6 ms (9 / 11) | 30 / 32 ms (37 / 45) |
| generated, 2000 FBs (≈ 240k lines, one model group) | 29–42 / 60–89 ms (125 / 266) | 430 / 560 ms (504 / 607) |

An edit to one of the 2000 FBs declares and binds that FB and `MAIN` (which holds all 2000 instances) again; the rest
is the fixed cost of a build over a big group (copying the side tables, the instance graph). On real repositories
(`modelCheck`, random edits): TC-Robot-Control-System (98k lines with a 7.6 MB generated FB) 11 ms median for an
incremental build against 650 ms–1.7 s for a full one; other TwinCAT/CODESYS repositories of 270–1,100 files 0–28 ms.
Between 30% and 100% of edits are incremental, most often 90% or more; the rest add or rename names (a full build
then, as before).

## Testing

- `plc-analysis`: `WorkspaceTest` (a full run after edits equals `plca analyze`), `NavigatorTest` (golden queries and
  outlines on the semantic test projects of all four languages), `WorkspaceFuzzTest` (Jazzer: random edits, runs and
  queries).
- `plc-lsp`: `LanguageServerTest` drives the server over JSON-RPC on pipes.
- `./gradlew :plc-devtools:navigationCheck --args="<dir> ..."`: queries at sampled positions of real projects.
- `editors/vscode`: `./gradlew :editors:vscode:grammarTest` (TextMate snapshots) and `:editors:vscode:e2eTest` (the
  packaged server in a downloaded VS Code; needs a display or `xvfb-run`). `scripts/ci.sh` runs the grammar tests and
  packaging when npm is installed, the e2e test with `PLCA_E2E=1`.
