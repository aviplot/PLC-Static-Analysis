# plca — static analysis for PLC programs

plca reads the source code of industrial controllers (PLCs) as it is exported from the vendor IDEs and checks it for
bugs, risky patterns and security smells. It is for the people who write and review PLC logic: controls engineers,
system integrators, plant owners and OT-security teams. Most of them have no code-level checking of that logic today.

It works like a classic static analyzer. Each language has its own front end that builds one shared model: POUs,
variables, types, call graph, read/write sets and tasks. The rules run on that model and report findings that point
at the exact line, rung or network the engineer sees in the IDE.

## Languages

| Vendor / format | Files | Read |
|---|---|---|
| CODESYS, generic IEC 61131-3 | `.st` | Structured Text (3rd edition, incl. OOP), Instruction List |
| Beckhoff TwinCAT 3 | `.TcPOU`, `.TcDUT`, `.TcGVL`, `.TcIO`, `.TcTTO` | ST, LD and FBD networks, CFC, SFC, tasks |
| Siemens TIA Portal | `.scl`, `.db`, `.udt` | SCL, data blocks, UDTs |
| Rockwell Studio 5000 | `.L5X`, `.L5K` | ladder (RLL), ST, FBD and SFC routines, tags, UDTs, AOIs, tasks |
| PLCopen TC6 XML (2.0, 2.01) | `.xml` | ST, IL, LD, FBD, SFC, CODESYS CFC |

Graphical languages (ladder, LD, FBD, CFC, SFC) are lowered to the same statements as ST, so every rule works on them
too. They are also drawn as SVG, in the HTML report and in the workbench, with the findings marked on the drawing.

## Rules

49 rules in four categories. Where a PLCopen Coding Guidelines rule applies, its id is given:

- **Quality**: unused variables and POUs, unreachable code, CASE without ELSE, REAL equality, division by zero, FOR
  variable written in the loop, writes to VAR_INPUT, recursion, loops without an evident exit, temporaries read before
  assignment, implicit narrowing, complexity, magic numbers and naming conventions (both opt-in).
- **PLC**: double coils, direct addressing, FB instances called twice per cycle, globals written from several tasks,
  pointer dereference without a check, jumps.
- **Security**: hard-coded credentials and IP addresses, externally writable values used without a check, suspicious
  hidden triggers (time bombs, magic values).
- **Logic**: conditions that are never or always true, nested or in an IF chain (ranges, enums and string lengths
  included), unreachable ELSIF / ELSE / CASE labels, duplicate and redundant conditions, boundary gaps, identical
  branches, self-assignment, loops that end in the first pass, overwritten temporaries; style findings (ELSE IF,
  nested IF, BOOL through IF, empty branches and others) at info; NOT before a comparison and BOOL compared with
  TRUE/FALSE are opt-in.

`plca rules` lists them; `plca rules PLCA-P001` shows a rule's documentation. Rules, severities, parameters,
suppressions and baselines are configured in `plca.yaml` ([docs/configuration.md](docs/configuration.md)).

## Getting started

Requirements: Java 21 or newer. Node/npm is needed only to build the VS Code extension and the web workbench.

```sh
./gradlew :plc-cli:installDist
modules/plc-cli/build/install/plca/bin/plca analyze path/to/project                        # console report
modules/plc-cli/build/install/plca/bin/plca analyze path/to/project -f sarif -o plca.sarif # SARIF 2.1.0 for CI
modules/plc-cli/build/install/plca/bin/plca analyze path/to/project -f html -o report.html # with drawings
```

`./start.sh` builds plca when needed and analyses a bundled demo project. Report formats are console, JSON
([schema](docs/report-json.schema.json)), SARIF and HTML. `--fail-on` sets the exit code for CI.

### Editor and workbench

- **VS Code** ([editors/vscode](editors/vscode)): live diagnostics, go to definition, references, hover, outline and
  highlighting, through the plca language server. `./gradlew :editors:vscode:package` builds `plca.vsix`. Other
  editors can start `plca lsp`. See [docs/lsp.md](docs/lsp.md).
- **Web workbench**: `plca web --open` opens a browser review tool. It reads a project export, lists the findings with
  filters, shows code navigation and ladder/network/SFC drawings, and compares two versions. Uploaded code stays on the
  machine and is deleted at session end. See [docs/web.md](docs/web.md).

## Building and testing

```sh
./gradlew build      # compile, tests (golden files, fuzz seeds), Spotless, Checkstyle
scripts/ci.sh        # what CI runs, including the editor tests when Node is available
```

The analyzer is a Gradle multi-module build (Java 21, ANTLR 4, StAX, picocli, Jackson, LSP4J):

| Module | |
|---|---|
| `plc-core` | sources, spans mapped back into XML exports, diagnostics |
| `plc-grammar` | ANTLR grammars: a shared ST core with CODESYS, Siemens SCL and Rockwell ST dialects; Rockwell rung text |
| `plc-ast` | the AST and the graphical IR (ladder, networks, SFC) |
| `frontend-st`, `frontend-scl`, `frontend-l5x`, `frontend-plcopen` | one front end per format |
| `plc-semantic` | symbols, types, call graph, read/write sets, task model |
| `plc-rules`, `plc-analysis`, `plc-report` | rules, the analysis pipeline, reports and drawings |
| `plc-cli`, `plc-lsp`, `plc-web` | command line, language server, web workbench |
| `plc-devtools` | corpus checks, benchmarks (JMH) and debugging tools (not shipped) |

Design notes per format are in [docs/grammar](docs/grammar), and the plan of each milestone is in
[docs/plans](docs/plans). Front ends are tested against a corpus of public PLC code harvested from GitHub and GitLab
by [tools/corpus-harvester](tools/corpus-harvester). That code belongs to its authors and is never committed here; the
harvester rebuilds the corpus locally.

## License

Copyright (C) 2026 Avraham Plotnitzky

plca is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as
published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
This applies to the analyzer, the VS Code extension and the web workbench.

plca is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License in [LICENSE](LICENSE) for more
details.

Third-party libraries keep their own licenses (ANTLR: BSD; Jackson, Woodstox, picocli, gson: Apache-2.0; LSP4J:
EPL-2.0 or EDL-1.0; Jetty: EPL-2.0 or Apache-2.0; Monaco, Preact: MIT).
