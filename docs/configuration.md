# Configuring plca

`plca analyze <dir>` reads `plca.yaml` from the analysed directory when it exists (`--config FILE` names another file,
`--no-config` ignores it). Command-line options win over the file.

## `plca.yaml`

```yaml
rules:
  select: [default, PLCA-Q014]     # ids, categories, default, all (default: default)
  disable: [PLCA-Q002]
  severity: {PLCA-Q013: warning}   # error, warning, info, hint
  parameters:
    PLCA-Q013: {maxNesting: 4, maxComplexity: 15}
    PLCA-Q014: {allowed: [10, 100, 3.6]}
  dialects:                        # codesys, twincat, siemens, rockwell, plcopen
    rockwell:
      severity: {PLCA-Q003: warning}
      disable: [PLCA-Q001]
suppress:
  - rule: PLCA-P002
    path: "io_mapping/**"          # glob over paths relative to the analysed directory
    reason: generated I/O mapping
  - rule: quality
    pou: "FB_Vendor*"              # glob over POU names, case-insensitive
baseline: plca-baseline.json       # relative to plca.yaml
fail-on: warning                   # error (default), warning, info, never
```

- **Selection.** `default` is every rule that is on by default; `all` adds the opt-in style rules (PLCA-Q014 magic
  numbers, PLCA-Q015 naming conventions, PLCA-L024 NOT before a comparison, PLCA-L025 BOOL compared with a literal).
  A category (`quality`, `plc`, `security`, `logic`) means its rules that are on by default; opt-in rules are selected
  by id. `plca rules` lists the rules, whether each is on by default and its PLCopen rule.
- **Per dialect.** `dialects.<dialect>` takes `select`, `disable`, `severity` and `parameters`, applied to POUs of that
  dialect on top of the global settings. The shipped defaults already differ in one place: PLCA-Q003 (REAL equality)
  is `info` for Rockwell, where exact comparisons are mostly deliberate sentinel checks.
- **Precedence.** Severity: dialect setting, then global setting, then the rule's shipped dialect default, then its
  default. Parameters: rule default, then global, then dialect. On the command line, `--rules` replaces the file's
  `select` and `disable`, `--disable` adds to `disable`, `--param RULE.NAME=VALUE` overrides parameters, `--fail-on`
  and `--baseline` replace the file's values.
- **Errors.** Unknown keys, rules, parameters, dialects and bad values stop the run with exit code 2 and the file and
  line, e.g. `plca.yaml:2: unknown key 'slect' in rules`.

## Suppressing findings in the code

A suppression names the rules it silences (ids, categories or `all`); one that names none is ignored with a warning.
Text after the rule ids is the reason and appears in the reports.

| Form | Silences |
|---|---|
| `// plca-disable-next-line PLCA-Q003 sentinel value` | findings starting on the next line |
| `x := y; // plca-disable-line PLCA-Q003` | findings starting on the same line |
| `(* plca-disable PLCA-P001 *)` … `(* plca-enable PLCA-P001 *)` | findings in between (to the end of the file when not re-enabled) |
| `{attribute 'plca-disable' := 'PLCA-Q001, PLCA-Q006'}` before a POU | findings in that POU and its methods and actions |
| the same attribute before a variable or VAR block | findings on that declaration |
| a Rockwell rung comment containing `plca-disable PLCA-P001` | findings in that rung |

Any comment of the language works (`//`, `(* *)`, `/* */`); comments of the XML container itself (`<!-- -->` in an
L5X or PLCopen file) are not read.
Suppressed findings are counted in every report; `--show-suppressed` lists them in the console report, the JSON
report has them in `suppressed[]`, and SARIF keeps them with a `suppressions` entry (`inSource` or `external`).

## Drawings in the HTML report

`--format html` draws the rung, PLCopen LD/FBD network or SFC chart of every finding that lies in one, below the
finding's source lines, with the element that holds the finding outlined in its severity's colour. A part with several
findings is drawn once, under its first finding; the others link to it. After 2,000 drawings (or 20 MB of them) the
rest of the findings show their source text only, and the report says so. `--report-diagrams none` gives the report
without drawings.

## Baselines

To adopt plca on existing code, record today's findings once and report only new ones from then on:

```sh
plca analyze project --write-baseline plca-baseline.json
plca analyze project --baseline plca-baseline.json --fail-on warning
```

The baseline stores a fingerprint per finding (rule, POU, the normalised text of the flagged line, occurrence), so
findings keep matching when code moves to other lines; editing the flagged line makes the finding new. The file is
sorted JSON that diffs well under version control. Reports count the findings in the baseline and the baseline entries
that no longer occur ("fixed since"); SARIF marks findings `baselineState: new` or `unchanged`.

## Report formats

`--format console` (default), `json` (schema in `docs/report-json.schema.json`), `sarif` (SARIF 2.1.0, for GitHub code
scanning and IDEs) and `html`: one self-contained file with a summary, filters by severity, rule, language and text,
source excerpts (the decoded rung or ST text for XML exports, numbered with the file's lines) and every rule's
documentation. It loads nothing from the network and sends nothing anywhere.

## Projects with several Rockwell controllers

Each Rockwell controller export (an L5X with `TargetType="Controller"`) is analysed as a project of its own, so two
versions of one controller in a folder do not merge into duplicate tags and cross-file double coils. Partial exports
(routines, programs, Add-On Instructions) join the controller when the folder holds exactly one, and the shared project
otherwise.
