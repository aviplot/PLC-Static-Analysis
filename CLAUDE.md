# PLC Static Analyzer — Project Brief for Claude Code

## What we are building
A static code analyzer for PLC (industrial controller) programs. It parses source exported from
vendor IDEs, builds a semantic model, runs quality and security rules, and reports findings.

Target users: controls engineers, system integrators, plant owners and OT-security teams who
today have little or no code-level checking of PLC logic.

The owner has years of experience building static analyzers for SAP ABAP in Java, and good
experience with ANTLR. Explain design choices with reference to classic analyzer architecture;
skip beginner explanations of Java or ANTLR.

## Supported languages (in priority order)
| # | Language / dialect | Input format | Notes |
|---|---|---|---|
| 1 | **CODESYS Structured Text** (IEC 61131-3 ST) | `.st` plain text; TwinCAT `.TcPOU` / `.TcDUT` / `.TcGVL` / `.TcIO` (XML with ST in CDATA) | First target. Covers Beckhoff, Schneider Machine Expert, WAGO, ABB, etc. |
| 2 | **Siemens SCL** | `.scl`, `.db`, `.udt` source exports from TIA Portal | ST dialect: quoted names (`"Motor_DB".speed`), `#local` vars, absolute addressing (`%DB1.DBX0.0`), REGION, Siemens pragmas. |
| 3 | **Rockwell Ladder (RLL) + ST via L5X** | `.L5X` XML export from Studio 5000 | Rung text is neutral text: `XIC(Start)XIO(Stop)OTE(Motor);` — its own small ANTLR grammar. Also handle `Type="ST"` routines, tags, UDTs, AOIs. |
| 4 | **PLCopen XML (TC6, v2.01)** | `.xml` | Exchange format. ST bodies + graphical LD/FBD/SFC (nodes with `localId` and connections). |

## Implementation stack
- **Java 21+** (current LTS), **Gradle** multi-module build (Kotlin DSL) with the `antlr` plugin.
- **ANTLR 4** for ST, SCL, L5X ST routines and Rockwell rung text.
- XML: **StAX** streaming (L5X files can be hundreds of MB — never load them into a DOM).
- CLI: **picocli**. JSON: **Jackson**. Parallelism: a fixed thread pool, one parser instance per thread.
- Testing: **JUnit 5 + AssertJ**, golden-file (snapshot) tests for parse trees/ASTs and findings,
  **Jazzer** for fuzzing the parsers, **JMH** for benchmarks.

## Architecture
```
modules/
  plc-core/          # spans, diagnostics, source map (file -> offsets of embedded text in XML), Dialect enum
  plc-grammar/       # ANTLR grammars (.g4) — see Grammar rules below
  plc-ast/           # our own AST for ST-family languages + Ladder/FBD IR (NOT the ANTLR parse tree)
  frontend-st/       # CODESYS ST: ANTLR parse -> AST builder (+ TwinCAT XML unwrapping)
  frontend-scl/      # Siemens SCL: shared ST grammar + SCL extensions -> same AST
  frontend-l5x/      # L5X reader (StAX): tags, UDTs, AOIs, programs, routines; rung grammar -> IR; ST routines
  frontend-plcopen/  # PLCopen XML reader (StAX): ST bodies + LD/FBD/SFC graph lowering
  plc-semantic/      # symbol tables, scopes, type resolution, call graph, read/write sets, task model
  plc-rules/         # rule engine + rules
  plc-report/        # JSON, SARIF 2.1.0, HTML, console
  plc-cli/           # `plca analyze <path> --format sarif --rules <config>`
corpus/              # test inputs (see Corpus)
```

### Grammar rules
1. **Separate lexer and parser grammars** (`StLexer.g4`, `StParser.g4`).
2. ST keywords are **case-insensitive**: use `options { caseInsensitive = true; }` in the lexer (ANTLR 4.10+).
3. **Shared core + dialect extensions**: a common ST grammar imported by dialect grammars
   (`CodesysSt`, `SiemensScl`, `RockwellSt`). Use grammar `import`, not copy-paste. Use semantic
   predicates only for small differences; if a dialect needs many predicates, give it its own grammar file.
4. Comments (`(* *)`, `//`, nested comments in CODESYS) and pragmas (`{attribute ...}`, `{IF defined ...}`)
   go to **hidden channels**, not `skip` — rules and suppressions need them later.
5. **Keep the grammar unambiguous.** Add a CI test that runs the corpus with
   `PredictionMode.SLL` and fails if the SLL-fallback rate rises above a small threshold.
6. Rockwell rung text gets its own tiny grammar (`RungLexer.g4` / `RungParser.g4`):
   instructions, branches `[ , ]`, operands, tag paths with array indexes and bit access.

### Design rules
1. **Two-stage parsing for speed**: SLL + `BailErrorStrategy` first; on failure, re-parse with full LL
   and a custom recovery strategy. Measure both paths in JMH.
2. **Custom error strategy**: real exports are messy. Recover by syncing to `;`, `END_IF`, `END_CASE`,
   `END_FOR`, `END_VAR`, `END_FUNCTION_BLOCK`, etc. Emit a diagnostic and continue; never throw out of
   the analyzer on bad input.
3. **Rules never run on ANTLR parse trees.** A visitor builds our own AST; everything downstream
   (semantic model, rules, reports) depends only on `plc-ast`. This keeps grammar refactors cheap.
4. **One semantic model for all four languages.** Ladder rungs are lowered to the same IR as ST
   (boolean expressions + assignments + instruction calls), so most rules are written once.
5. **Precise spans always**, mapped back to the original file and line — including text embedded
   in XML CDATA or `<Text>` elements. Findings must point to what the engineer sees in the IDE.
6. **Dialect differences are explicit**: a `Dialect` enum (Codesys, TwinCat, Siemens, Rockwell, PlcOpen)
   travels with every POU; rules can opt in/out per dialect.
7. **Performance is a feature.** Target: 1M lines of ST in < 10 s on a laptop. Parse files in parallel,
   reuse lexer/parser instances per thread, avoid keeping parse trees after AST building.
   Add JMH benchmarks from M2 onward.

## Semantic model must provide
- POUs: PROGRAM, FUNCTION_BLOCK, FUNCTION, METHOD, PROPERTY, INTERFACE, ACTION; Siemens OB/FB/FC/DB; Rockwell programs/routines/AOIs.
- Variables by section (VAR_INPUT/OUTPUT/IN_OUT/GLOBAL/TEMP/STAT/CONSTANT/EXTERNAL), with types,
  initial values, and attributes/pragmas.
- User types: STRUCT, ENUM, UNION, ALIAS, arrays, POINTER TO / REFERENCE TO, Rockwell UDTs.
- Read/write sets per statement/rung, call graph, FB-instance graph, task-to-POU mapping (where the
  project export contains task configuration) — needed for race-condition rules.

## Initial rule set (target ~25 for the first demo)
Map rules to the PLCopen Coding Guidelines where one applies — verify the rule IDs against the
official document; do not invent IDs.

Quality / correctness
- Unused variable / unused POU / unreachable code (after RETURN, constant-false IF)
- Variable read before assignment (no initial value)
- CASE without ELSE
- Equality comparison on REAL/LREAL
- Implicit narrowing type conversion
- Division by a literal or constant zero
- WHILE/REPEAT loop with no evident exit condition; FOR loop variable modified inside the body
- Recursion (direct or indirect)
- FB instance called more than once per cycle
- Writing to a VAR_INPUT inside the POU
- Nesting depth and cyclomatic complexity over threshold
- Magic numbers (configurable)
- Naming conventions (configurable prefixes: `b`, `i`, `fb`, `st`, etc.)

PLC-specific
- **Double coil**: same output/tag written in more than one place (rungs or statements)
- Direct/absolute addressing (`%I`, `%Q`, `%M`, Siemens `%DB…`) instead of symbolic
- Timer/counter instance reused in multiple places
- Pointer dereference without a nonzero check
- Global variable written from POUs running in different tasks (potential race)
- JMP / LABEL usage

Security-oriented
- Hard-coded credentials/IPs in string literals
- Outputs driven directly from external/HMI-writable tags with no range or validity check
- Suspicious hidden logic: code that triggers on time/date or on rarely-set magic values

Each rule: id, title, description, severity, dialects, rationale, compliant/non-compliant examples,
and tests (positive + negative) in `plc-rules/src/test/resources/<rule_id>/`.

## Corpus harvest (P0)
Build `tools/corpus-harvester/` (a small standalone Java CLI, or a Python script if faster — it is not
shipped with the product). It must:

1. **Search** public sources using official APIs only, with an auth token from an env var
   (`GITHUB_TOKEN`), respecting rate limits (back off on 403/429, resume from a checkpoint file):
   - GitHub code/repo search by file type: `.TcPOU`, `.TcDUT`, `.TcGVL`, `.TcIO`, `.plcproj`, `.tsproj`
     (TwinCAT); `.scl`, `.db`, `.udt` (Siemens); `.L5X`, `.L5K` (Rockwell); PLCopen XML (files containing
     the `http://www.plcopen.org/xml/tc6_0201` namespace); `.st` (CODESYS/generic ST).
   - GitHub topics/keywords: `twincat`, `codesys`, `plc`, `structured-text`, `iec-61131-3`, `tia-portal`,
     `studio5000`, `plcopen`.
   - Known libraries: OSCAT and similar public ST libraries.
   - GitLab public search via its API as a second source.
2. **Filter noise.** `.st` is also Smalltalk and other formats — keep a file only if the content matches
   ST markers (`FUNCTION_BLOCK`, `END_VAR`, `PROGRAM`, `END_IF`, etc.). Same idea for `.db`/`.udt`
   (require SCL markers) and XML (require the right root element/namespace).
3. **Fetch** with shallow clones (`--depth 1`) or per-file downloads; keep only matching files plus the
   repo's LICENSE file; delete everything else.
4. **Record** every file in `corpus/MANIFEST.csv`: repo URL, commit SHA, path, detected dialect,
   SPDX license (from the GitHub license API; `NOASSERTION` if none), size, lines, SHA-256.
5. **Deduplicate** by content hash (vendor libraries get copied into many repos); keep the first source.
6. **Report** `corpus/STATS.md`: file and line counts per dialect, per license, top repos, and gaps.

License policy — enforce in the harvester, not by hand:
- **Permissive (MIT, BSD, Apache-2.0, Unlicense, CC0):** may be used in tests and committed as small
  fixtures, with attribution in `corpus/SOURCES.md`.
- **Copyleft (GPL, LGPL, etc.) or no license:** local analysis and benchmarking only. Never commit to
  this repo, never bundle, never copy into fixtures or rule examples.
- The whole `corpus/` directory is **git-ignored**: nothing from the corpus is committed, not the
  harvested code in `corpus/external/` and not the generated `MANIFEST.csv`, `STATS.md`, `SOURCES.md`.
  Only the harvester code is committed; it regenerates the corpus.
- Do not scrape sites whose terms forbid it, and do not touch login-protected vendor portals or forums.

## Corpus
- Real-world code from the P0 harvest (see license policy above).
- Hand-written examples for every grammar feature and every rule (owned by us).
- Open-source ST: e.g. the OSCAT library and public TwinCAT projects on GitHub. Check the
  `antlr/grammars-v4` repo for any existing IEC 61131 grammar as a reference only — we write our own.
  **Check the license of every repo before adding it**; record source and license in `corpus/SOURCES.md`.
  Store third-party code under `corpus/external/` and keep it out of release artifacts.
- Self-made exports from free IDEs (CODESYS is free; TwinCAT XAE is free to install).
- Siemens and Rockwell samples: synthetic files written to match the documented export formats until
  real exports are available.

## Milestones
- **P0 — Corpus harvest (pre-step, before any parser work).** Collect as much real-world PLC code as
  possible, so grammars are designed against real code, not just the spec. See "Corpus harvest" below.
  Done when: harvester runs end to end, `corpus/MANIFEST.csv` and `corpus/STATS.md` exist, and there is
  real code for all four languages (or a documented gap with synthetic files planned).
- **M0** Gradle multi-module scaffold, CI (build, tests, Spotless/Checkstyle), `plc-core` spans/diagnostics.
- **M1** CODESYS ST grammar + AST builder: full IEC 61131-3 3rd-edition ST incl. OOP (METHOD, INTERFACE,
  EXTENDS, IMPLEMENTS), pragmas on hidden channel, TwinCAT XML unwrapping, two-stage parsing,
  custom error strategy. Jazzer fuzzing on.
- **M2** Semantic model for ST. JMH benchmarks.
- **M3** Rule engine + first 10 rules + JSON/SARIF output + CLI. **← first demoable build**
- **M4** Siemens SCL as a dialect grammar on the shared ST core.
- **M5** L5X frontend: tags/UDTs/AOIs, rung grammar lowered to IR, ST routines.
- **M6** PLCopen XML frontend incl. LD/FBD graph lowering.
- **M7** Rest of the rule set, HTML report, rule config file (`plca.yaml`), baseline/suppression support.
- **M8** Language Server + VS Code extension.
  - New module `plc-lsp` using **Eclipse LSP4J**, built on the existing frontends/semantic model/rules
    (no duplicate analysis logic in the LSP layer).
  - Features: live diagnostics (rule findings), go-to-definition, find references, hover (type + declaration),
    document outline, workspace symbols; quick-fixes later.
  - Incremental: re-parse only the changed file, update the semantic model, re-run affected rules.
    Target < 200 ms from keystroke to diagnostics on a typical POU.
  - `editors/vscode/`: thin TypeScript extension that launches the server; TextMate grammar for ST/SCL highlighting.
- **M9** Web workbench ("PLC code review workbench" — not a full IDE).
  - Frontend: **Monaco** editor + `monaco-languageclient` talking to the M8 server over WebSocket.
    Monarch tokenizer for ST/SCL highlighting.
  - Open a project export (folder/zip of `.st`, TwinCAT files, `.scl`, `.L5X`, PLCopen XML), file tree,
    findings panel with filtering by rule/severity, click-to-navigate.
  - **Ladder viewer**: render L5X rungs (and PLCopen LD) as SVG from the rung IR, read-only at first,
    with findings highlighted on the rung.
  - Diff view between two versions of a project, with new/fixed findings.
  - Out of scope: compiling, downloading to a PLC, online/monitoring, hardware configuration.
  - Backend: small Java web server hosting the LSP over WebSocket plus upload/project endpoints.
    Uploaded customer code is sensitive: no third-party telemetry, delete uploads on session end by default.

## How to work in this repo
- Before a milestone, write a short plan in `docs/plans/Mx.md` and wait for approval.
- Grammar work: write notes in `docs/grammar/<dialect>.md` first (citing the relevant spec section),
  then tests, then grammar, then AST builder.
- Every grammar change ships with golden-file tests; every bug found by fuzzing becomes a regression test.
- Keep `./gradlew build` green (compile, tests, static checks) before committing.
- Don't guess vendor format details — if unsure about an L5X element, SCL syntax or PLCopen schema
  element, say so and ask, or mark it `// TODO(verify-format)`.
- Prefer small commits per feature with clear messages.
