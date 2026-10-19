# Language server and VS Code extension

`plca lsp` is a language server (LSP 3.17, stdio) over the same pipeline as `plca analyze`: frontends, semantic model,
rules, `plca.yaml`, suppressions and baseline. `editors/vscode/` is a thin VS Code client for it. Plan and design
decisions: [plans/M8.md](plans/M8.md).

## Using it

```sh
./gradlew :editors:vscode:package          # editors/vscode/plca.vsix, server jars included (needs Node/npm)
code --install-extension editors/vscode/plca.vsix
```

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
Commands: `plca.analyzeWorkspace` (re-read everything from disk), `plca.ruleDoc` (server: a rule's Markdown), and in
VS Code "Show documentation of PLCA-…" on every finding.

## How an edit is processed

1. The client sends the full text (full sync). The analysis thread parses that file only, with a frontend per thread,
   into a new `SourceRegistry` derived from the previous one (`without(path)`): ids are never reused, so older
   snapshots stay valid for queries in flight.
2. **Fast tier**, 100 ms after the last edit: a snapshot (`Workspace.snapshot()`) rebuilds the semantic model of the
   edited file's model group (other groups keep theirs), and the POU rules run on the POUs of the edited files. The
   file's parse, model and rule diagnostics are published with the document version.
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

JMH `EditCycleBenchmark` (`./gradlew :plc-devtools:jmh -Pjmh.args=EditCycleBenchmark`), one thread:

| Project | Fast tier p50 / p95 | Project tier p50 / p95 |
|---|---|---|
| generated, 200 FBs (≈ 24k lines) | 9 / 11 ms | 37 / 45 ms |
| generated, 2000 FBs (≈ 240k lines, one model group) | 125 / 266 ms | 504 / 607 ms |
| TwinCAT repository, 98k lines incl. a 7.6 MB generated FB | 649 / 798 ms | 1.9 / 2.6 s |

The fast tier rebuilds the whole model group: model build time grows with the group, not with the edit. Typical
projects are far inside the 200 ms target; very large groups, and projects with huge generated POUs, are not. The
follow-up is an incremental model that re-binds only changed POUs (M8 "As built").

## Testing

- `plc-analysis`: `WorkspaceTest` (a full run after edits equals `plca analyze`), `NavigatorTest` (golden queries and
  outlines on the semantic test projects of all four languages), `WorkspaceFuzzTest` (Jazzer: random edits, runs and
  queries).
- `plc-lsp`: `LanguageServerTest` drives the server over JSON-RPC on pipes.
- `./gradlew :plc-devtools:navigationCheck --args="<dir> ..."`: queries at sampled positions of real projects.
- `editors/vscode`: `./gradlew :editors:vscode:grammarTest` (TextMate snapshots) and `:editors:vscode:e2eTest` (the
  packaged server in a downloaded VS Code; needs a display or `xvfb-run`). `scripts/ci.sh` runs the grammar tests and
  packaging when npm is installed, the e2e test with `PLCA_E2E=1`.
