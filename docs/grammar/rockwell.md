# Rockwell Logix 5000 (L5X) — format and grammar notes

References:
- **Logix 5000 Controllers Import/Export Reference Manual**, 1756-RM084 (the L5X schema: `RSLogix5000Content`,
  `Controller`, `DataTypes`, `AddOnInstructionDefinitions`, `Tags`, `Programs`, `Tasks`, `Modules`). Cited as *RM084*.
- **Logix 5000 Controllers General Instructions Reference Manual**, 1756-RM003: operands of the ladder instructions.
  Cited as *RM003*. Motion, safety and process instructions have their own manuals (MOTION-RM002, 1756-RM006, …);
  they are not modelled in detail.
- **Logix 5000 Controllers Structured Text Programming Manual**, 1756-PM007. Cited as *PM007*.
- **Corpus evidence**: 2,052 `.L5X` files from 169 repos, survey scripts run on 2026-10-19 (not committed). Counts
  are files, routines or rungs as stated. Two generator repos (`jholm90/logixMemoryMap`, `johnpuksta/l5x-lint`) hold
  96% of the 3.05M rungs, so rung counts below are given **without** them where that matters, and every rate is
  repo-weighted.

Points I have not checked against the manual text are marked `TODO(verify-format)`; they are listed in §9.

## 1. The XML container (RM084)
| Element | What we take | Evidence |
|---|---|---|
| `RSLogix5000Content` | `TargetType` (Controller, AddOnInstructionDefinition, DataType, Program, Routine, Rung, Module, Tag), `TargetName`, `SoftwareRevision`, `ContainsContext` | Controller 639, AOI 491, DataType 319, Routine 202, Program 165, Module 145, Rung 56, Tag 9 files |
| `Controller` | `Name`, `ProcessorType`; holds the sections below | |
| `DataTypes/DataType` | `Name`, `Family` (`NoFamily`, `StringFamily`), `Class`; `Members/Member`: `Name`, `DataType`, `Dimension`, `Hidden`, `Target`, `BitNumber`, `ExternalAccess`, `Description` | 1,103 files |
| `AddOnInstructionDefinitions/AddOnInstructionDefinition` | `Name`, `Revision`, `Class` (Standard/Safety), `ExecutePrescan/Postscan/EnableInFalse`; `Parameters/Parameter` (`Name`, `TagType`, `DataType`, `Dimensions`, `Usage` = Input/Output/InOut, `Required`, `Visible`, `ExternalAccess`, `DefaultData`); `LocalTags/LocalTag`; `Routines/Routine` named `Logic`, `Prescan`, `Postscan`, `EnableInFalse` | 1,761 AOIs in 753 files; 15.8k Input, 12.3k Output, 3.1k InOut parameters |
| `Tags/Tag` (controller scope; also inside `Program`) | `Name`, `TagType` (Base 566k, Alias 7.8k, Produced 57, Consumed 48), `DataType`, `Dimensions`, `AliasFor`, `Usage` (program parameters: Input/Output/InOut/Public, v24+), `Constant`, `ExternalAccess`, `Class` (Safety), `Description`, `Data` | |
| `Programs/Program` | `Name`, `MainRoutineName`, `FaultRoutineName`, `Disabled`, `UseAsFolder`, `Class` (Standard/Safety); `Tags`, `Routines` | 2,672 programs; safety in 208 files; fault routine in 10 |
| `Routine` | `Name`, `Type` = RLL 32,920 / ST 1,282 / FBD 143 / SFC 65 / Encrypted 4 | |
| `RLLContent/Rung` | `Number`, `Type` (`N` normal; `D`, `I`, `IR`, `R`, `rr`, `e` are pending online-edit markers, 35 rungs in total), `Comment` (CDATA), `Text` (CDATA) | 1,534 files use CDATA for rung text |
| `STContent/Line` | `Number`, CDATA text of one line. Numbering starts at 0 in 1,413 of 1,446 routines; partial exports may start later | 75k lines |
| `Tasks/Task` | `Name`, `Type` (CONTINUOUS 426, PERIODIC 308, EVENT 26), `Rate` (ms), `Priority` (1–15), `Watchdog`, `InhibitTask`, `DisableUpdateOutputs`; `ScheduledPrograms/ScheduledProgram Name`; `EventInfo` (`EventTrigger`, `EventTag`) | |
| `Modules/Module` | `Name`, `CatalogNumber`, `ParentModule`; connection `InputTagSuffix`/`OutputTagSuffix`. Only used to recognise module names (§5) | 652 files |
| `EncodedData` | source-protected AOI (71 files) or routine (10). The interface may be present outside it; the body is not readable | |

- Partial exports (`TargetType` other than Controller) wrap the target in its context (`Use="Context"` on enclosing
  `Programs`/`Program`/`Routines`). The reader treats `Use="Context"` and `Use="Target"` alike; whatever is present
  becomes part of the unit.
- **XML safety**: 2 corpus files are XXE probes (`malicious-xxe.l5x`, `xxe-big.l5x`), 11 more are truncated or
  invalid. The reader turns off DTD support and external entities (the TwinCAT reader's Woodstox setup) and turns any
  XML error into a diagnostic. Elements read before the error are kept.
- **Size**: the largest corpus file is 43 MB. The decoded text is kept (spans need it), the XML is never built into a
  DOM, and parse trees are dropped after AST building.
- Text inside `Rung/Text`, `Rung/Comment` and `Line` is taken from the raw file with `XmlText` (exact source map,
  CDATA or entity-escaped). The same goes for `Description` when a rule needs it later.

## 2. Mapping to the AST
| L5X | AST |
|---|---|
| Controller tags | `GlobalVars(listName = controller name, VarBlock GLOBAL)` |
| `DataType` | `TypeDecl(name, StructType(members))`. `Hidden="true"` members (the SINT hosts of BIT members) are dropped; `BIT` members become `BOOL` members. They are separate bits for every rule; their host is never written directly |
| AOI | `Pou(FUNCTION_BLOCK)`. Parameters by `Usage` → `INPUT`/`OUTPUT`/`IN_OUT` (EnableIn/EnableOut are listed in the export and kept), local tags → `VAR`. Body = the `Logic` routine; `Prescan`/`Postscan`/`EnableInFalse` → members of kind `ROUTINE` |
| Program | `Pou(PROGRAM)`. Tags → `VAR`, or `INPUT`/`OUTPUT`/`IN_OUT` by `Usage`; `Public` → `VAR PUBLIC`. Every routine → member of kind `ROUTINE`. Body = one synthetic `JSR(<MainRoutineName>);`, the program's scan. `FaultRoutineName`, `Class`, `Disabled` → attribute pragmas on the POU |
| Routine (RLL) | `Pou(ROUTINE)`, body = one `Statement.Rung` per rung (§4); `bodyLanguage = "RLL"` |
| Routine (ST) | `Pou(ROUTINE)`, body = the parsed statements; `bodyLanguage = "ST"` |
| Routine (FBD, SFC, encrypted) | `Pou(ROUTINE)` with `body = null`, `bodyLanguage = "FBD"`/`"SFC"`/`"Encrypted"`, plus an info diagnostic (FBD/SFC wait for M6) |
| Alias tag | `VarDecl.overlay` = the `AliasFor` path as written (`Local:2:O.Data.0`, `Arr[3].Run`, `Word.5`); the semantic model splits it into base tag + path |
| Tasks | `Configuration(controller)` with one `Task` per task (`PRIORITY`, `INTERVAL := T#<Rate>ms` for PERIODIC, `TYPE`) and one `ProgramInstance(program, task)` per scheduled program |
| Tag initial value | `Data Format="Decorated"` → `DataValue Value` for elementary tags; `Format="String"` CDATA for strings. Structures/arrays: no initializer in M5 |

## 3. Rung text (RM084 "neutral text"; own grammar `RungLexer`/`RungParser`)
```
rungs     : rung* EOF                       // one routine's rungs, joined with "\n" through the source map
rung      : element* ';'
element   : instruction | branch
branch    : '[' leg (',' leg)* ']'          // legs may be empty: [XIC(a),]
leg       : element*
instruction : mnemonic '(' operand (',' operand)* ')'
operand   : expression | '?' | /* empty */  // GSV(ControllerDevice,,DeviceName,x); NOP()
```
| Item | Rule | Evidence (rungs without the generators, 67.6k) |
|---|---|---|
| Mnemonic | Any identifier directly followed by `(`. Instruction names and AOI names share the space. 693 distinct; the top 16 cover > 99% of uses | |
| Whitespace | Allowed between elements and inside operands. Studio 5000 writes one blank before `,` and `]` in branches (`[XIC(a) ,XIC(b) ]`). Newlines inside rung text occur in 5 repos | |
| Branch nesting | Unbounded in the grammar. Depth 1: 11.4%, depth 2: 0.8%, depth ≥ 3: 0.3% of all rungs (max ≥ 6) | |
| `?` | Operand not set: preset/accum display values (`TON(t,?,?)`), or unconfigured operands | 6% |
| Empty operand | `GSV(ControllerDevice,,DeviceName,x)`: the instance name of a class without instances | |
| Tag path | `name`, `a.b.c`, `a[3]`, `a[i,j]`, `a[b.c]` (index expressions), `a.5` (bit), `a.[i]` (indirect bit), `Local:1:I.Data.5` and `Rack:I`, `S:FS` (module/status tags: the root has `:` parts), `\Program.Tag` (another program's parameter) | `.N` 26%, variable index 13%, module 7%, `\` 1% |
| Numbers | `123`, `-128`, `1.5`, `.6`, `1.17549435e-038`, `16#0FF0`, `2#…`, `8#…`, `10#1` (decimal radix written out; 1 repo with Decimal display style), underscores allowed | |
| Setting words | Safety and motion instructions take settings as words: `DCS(…,EMERGENCY STOP,EQUIVALENT - ACTIVE HIGH,…)`, `MCS(…,Units per sec2,…)`, `% of Maximum`. Rule `settingText`: at least two **adjacent** words (with optional `-` between others), so `A - B` stays a subtraction; built as an ENUM literal. Single-word settings (`Trapezoidal`, `Yes`, `Disabled`) parse as names; the semantic model treats an instruction operand made only of names that are no tag as a setting, not an unresolved name | 11 repos |
| Strings | `'LWO_3072'` (single quotes, `$` escapes as in ST) | 4 repos |
| Expressions | CPT/CMP/FAL/FSC operands and calculated indexes: `+ - * / MOD ** AND OR XOR NOT`, comparisons `= <> < <= > >=`, parentheses, function calls (`ABS(x)`, `SQR(x)`, `TRN(x)`…). Word operators are case-insensitive. **The rung grammar has its own small expression rules** (not a second parse with the ST grammar): one parse per routine keeps the rung path fast (§8). The rules mirror the ST precedence | 3% |
| Keywords | None at the lexer level that would stop a tag name. `AND`, `OR`, `XOR`, `NOT` and `MOD` are both instructions (`AND(a,b,dest)`) and expression operators; they are tokens and the `mnemonic` rule accepts them | |
| Empty rung | `;` alone (5 rungs). A rung whose `Text` is missing is an empty rung too | |

`Rung Type` other than `N` marks pending online edits (`I`/`IR` insert, `R`/`rr` replace, `D` delete,
`e` edit; TODO(verify-format)). These rungs are parsed like normal ones and the type is kept on the node. A project
with pending edits contains both the old and the new version of a rung, so a double coil between an `R` rung and its
`I` replacement is possible; it is reported as found.

## 4. Lowering ladder to statements (design rule 4)
Each rung is lowered from left to right with a *rung condition* (power flow) that starts at TRUE.

- **Input instructions** AND a term onto the condition: `XIC(a)` → `a`, `XIO(a)` → `NOT a`, `EQU/NEQ/GRT/GEQ/LES/LEQ`
  → `=`, `<>`, `>`, `>=`, `<`, `<=`, `CMP(e)` → `e`, `LIM`/`MEQ` → calls, `ONS(b)` → call (reads and writes `b`),
  `AFI()` → `FALSE`.
- **Branches**: each leg starts from the incoming condition; after the branch the condition is the OR of the legs.
- **Output instructions** become statements:

  | Instruction | Statement |
  |---|---|
  | `OTE(b)` | `b := cond;` (unconditional: the coil follows the rung) |
  | `OTL(b)` / `OTU(b)` | `IF cond THEN b := TRUE; END_IF` / `FALSE` |
  | `MOV(s,d)`, `CLR(d)`, `ADD/SUB/MUL/DIV/MOD(a,b,d)`, `AND/OR/XOR(a,b,d)`, `NOT(s,d)`, `NEG(s,d)`, `CPT(d,e)` | `IF cond THEN d := …; END_IF` (with `DIV`/`MOD` as binary expressions, so the zero-division rule sees them) |
  | `TON/TOF/RTO(t,…)`, `CTU/CTD(c,…)` | `TON(t, EnableIn := cond);`: executed every scan, the rung condition is the input. The preset/accum operands are display values and are not lowered |
  | AOI `Name(tag, args…)` | `Name(tag, args…, EnableIn := cond);` every scan. The following condition is `tag.EnableOut` |
  | `JSR(r, n, in…, ret…)` | `IF cond THEN JSR(r, n, in…, ret…); END_IF` |
  | anything else | `IF cond THEN MNEM(operands…); END_IF` (effects from the instruction table) |

- **Unknown instructions** (neither in the table nor an AOI of the export) are lowered as a condition term with side
  effects: what follows stays conditional, so no unconditional write is invented, and the call is still evaluated with
  possibly-written operands (a tool-generated `GE(a,b)OTU(x)` was otherwise lowered to an unconditional `x := FALSE`).
- Consecutive conditional outputs on the same condition share one `IF`: the rung condition is computed once
  (`XIC(R)MOV(0,R)MOV(5,X)` must still execute the second MOV). A trivial condition is stored in `$rcN` when an output of
  the rung writes the tag it reads (`XIC(A)OTU(A)OTE(B)`).
- A branch is built relative to its root (`A AND (B OR NOT C)`) unless an output inside a leg uses a leg's condition.
- **Each instruction appears exactly once** in the lowered statements. A condition used by more than one consumer is
  stored in a synthetic power-flow variable first (`$rc1 := …;`, `Identifier.Form.SYNTHETIC`), unless it is TRUE, a
  single name, `NOT name` or already synthetic. So rules never see an instruction twice (no duplicate findings), and
  side-effecting inputs like `ONS` keep their scan order. A rung whose final condition contains a side effect but
  feeds no output keeps it as `$rcN := …;`.
- Spans: every lowered statement has its instruction's span; operand expressions keep their own spans. A finding on a
  lowered statement points at the instruction in the rung text.
- The rung keeps its `Ladder` (series/branch/instruction tree) for the M9 viewer and for rung-level rules.

### Instruction table (`plc-ast`, `dev.plca.ast.ladder.InstructionSet`)
Data-driven (`instructions.txt` resource): mnemonic, class (input / output / AOI-like), and per operand a role:
`r` read, `w` write, `rw` read and write, `inst` instance (read and written: TIMER, COUNTER, CONTROL, MESSAGE, …),
`name` (not a tag: GSV class/attribute names, routine names, labels), `expr`, `disp` (display value, not lowered),
`n` (count), `var` (the rest of the operands, JSR-style). Entries come from RM003 for the instructions with the
highest repo counts in the corpus (§10). A mnemonic in neither the table nor the project's AOIs is an *unknown
instruction*: its operands become `UNKNOWN` accesses (counted as used, never as a write), so a double coil is neither
invented nor hidden by a guess.

## 5. Names and scoping (semantic model)
- Tag names are case-insensitive (RM084; Studio 5000 refuses tags that differ only in case, TODO(verify-format)).
- A routine sees its program's tags first, then controller tags. An AOI routine sees only its parameters and local
  tags (and controller-scope? no: AOIs cannot reference controller tags, RM084/1756-PM010, TODO(verify-format)),
  but still the global types and AOIs.
- `\Prog.Tag`: the parameter `Tag` of program `Prog` (program parameters, v24+).
- Module tags `Name:Slot:I`, `Name:I1`, `Local:1:O` are created by the I/O configuration. Their layout is defined by
  the module profile and is not in the export (only data values are), so they become I/O symbols of unknown type:
  area from the last `:` part (`I` input, `O` output, `C` config, `S` status). `S:FS`, `S:N`, `S:Z`, `S:V`, `S:C`,
  `S:MINOR` are controller status flags (first scan, arithmetic flags).
- Rockwell instructions and data types (`TIMER`, `COUNTER`, `CONTROL`, `MESSAGE`, `PID`, `STRING` with `LEN`/`DATA`,
  `FBD_TIMER`, …) are Rockwell builtins, looked up only for POUs of dialect ROCKWELL.

## 6. Structured Text routines (PM007; `RockwellStLexer`/`RockwellStParser` on the shared core)
| Item | Rule | Evidence (routines / repos) |
|---|---|---|
| Entry | `statementsOnly`: no POU header or VAR sections; declarations are the tags in the XML | |
| Comments | `//`, `(* *)`, `/* */`. They do **not nest** (unlike the core): a Festo library opens `(*` and puts `(*…*)` lines inside, relying on the first `*)` to close; with nesting the rest of the routine became a comment. The Rockwell lexer replaces the core's comment rules | 481/41, 176/19, 715/11 |
| `#region` | `#region text` / `#endregion` lines (ST editor folding) go to the pragma channel as REGION pragmas | 2 repos |
| Setting words | `%ofMaximum` etc. in motion instruction calls: one token, an identifier for the parser | 1 repo |
| Empty arguments | `GSV(WallClockTime, , CurrentValue, TNow)`: an argument may be left out, as in rungs | 3 repos |
| Statements | core IF/ELSIF/ELSE, CASE (numeric labels, ELSE), FOR…BY, WHILE, REPEAT, EXIT, RETURN | CASE 61/16, FOR 179/22, WHILE 115/7, REPEAT 3/3, EXIT 76/12, RETURN 1/1 |
| Non-retentive assignment | `a [:=] b;` (resets `a` at prescan/power-up). Token `NONRETENTIVE_ASSIGN`, `AssignOp.NON_RETENTIVE`; same effects as `:=` | 3/3 |
| Names | core names plus the module/status roots (`Local:1:I.Data`, `S:FS`) and `\Prog.Tag` as in §3. A `:` belongs to a module path only when no whitespace surrounds it (CASE labels like `1:` are unaffected). Routine bodies contain no declarations, so all declaration keywords of the core are soft identifiers (`Ref_Instance.Type`, a tag `Var`), and so are `THIS`, `SUPER`, `TRUE` and `FALSE` (one project defines `TRUE := 1;`; in an expression the literal wins) | module tags 14/6, `\` 5/3 |
| Indirect bit | `x.[i]` | 14/6 |
| Calls | instructions as calls: `TONR(t)`, `JSR(r, n, …)`, `COP(a, b, n)`, `SIZE(a, 0, n)`, `GSV(…)`, `MSG(m)`, AOIs `Name(tag, args…)` | TONR 19 repos, COP 11, SIZE 10, GSV 9, JSR 7 |
| Operators | core: `**`, `NOT`, `* / MOD`, `+ -`, comparisons, `AND`/`&`, `XOR`, `OR`; `16#`, `2#`, `8#` | |
| Not in Rockwell ST | pragmas, typed literals, time literals, OOP, pointers, labels/jumps. They stay in the core grammar (unused) | 0 |

The ST editor numbers lines from 1 while the export numbers them from 0 (TODO(verify-format)). Findings use the
physical L5X line plus the logical location `Program:P/Routine:R/Line:n` with `n = Number + 1`.

## 7. Error recovery
- XML: a well-formedness error ends reading. Everything read before it is analysed, plus one error diagnostic.
- Rungs: recovery syncs on `;` (and stops at `,`, `]`, `)` inside a rung), so one bad rung costs one diagnostic. A
  rung with a syntax error anywhere in its text keeps its number but no drawing, and its lowering is one `Invalid`
  statement (a half-recovered drawing would be lowered into nonsense). A rung text without its closing `;` is closed by
  the frontend with a warning (`PLC-L5X-004`), so it cannot swallow the next rung.
- ST: the core recovery strategy with the usual `;`/`END_*` sync set.
- Never throws (design rule 2): reader, parsers, lowering and semantic mapping report internal failures as
  `PLC-SYN-999`-style diagnostics and continue with the next routine.

## 8. Performance
One `SourceText` per routine (rungs joined with `\n`, not one per rung): the corpus has 3M rungs, and a text per rung
would put millions of entries into the source registry. One parse per routine, two-stage (SLL first).

## 9. Open points (TODO(verify-format))
1. `Rung Type` letters other than `N`: meaning of `e`, `rr`, `dN` (seen 1–17 times).
2. ST editor line numbering vs. `Line Number` (0-based export, 1-based editor?).
3. Tag-name case rules and whether AOIs may reference controller-scope tags (assumed: no, except via parameters).
4. Module tag naming (`Local:1:I`, `Adapter:2:I`, `Name:I1`): treated generically as "root with `:` parts".
5. `[:=]` effects at prescan: modelled as a plain assignment.
8. `=>` inside a CMP expression (`CMP(a => (b - c))`, 3 rungs in one customer export): accepted by Logix? Reported as a
   syntax error until confirmed.
9. TRUE/FALSE: tag names or boolean literals in Logix ST? Both are accepted (a project assigns `TRUE := 1;`).
6. Operand roles of the instruction table entries marked `?` in `instructions.txt`.
7. AOI call operands: **verified from the corpus**: backing tag, then the `Required` parameters in definition order
   (EnableIn/EnableOut excluded): 11,437 of 11,452 rung calls of AOIs defined in the same file match; the 15 others
   are hand-written test files.
10. Ladder `FOR` with four operands, `FOR(index,initial,terminal,step) … NXT` (seen in corpus exports, e.g. a tutorial
    project explaining it): modelled as a loop that writes its index, unlike the routine form
    `FOR(routine,index,initial,terminal,step)`. Not yet checked against 1756-RM003 (M7).

## 10. Corpus results (M5)
`./gradlew :plc-devtools:corpusCheck -Ponly=Rockwell` (one project per L5X file: an export is a complete project), 2,052
files from 169 repos, 17.8M lines, 59 s on 8 threads including model and rules:

| measure | overall | repo-weighted | repos |
|---|---|---|---|
| L5X files without any syntax error | 97.95% | 97.24% | 169 |
| rungs (3,058,701) parsed without error | 100.00% | 99.57% | 134 |
| ST routines (1,275) parsed without error | 99.76% | 99.45% | 71 |
| names resolved / library / external / unresolved | 98.64% / 0.97% / 0.28% / 0.11% | 98.36% resolved+library+external | |

SLL fallbacks: 34 of 34,065 routine texts, all with real syntax errors (no clean fallback). The remaining failures are
not Studio 5000 syntax: C operators (`&&`, `||`) in tool-generated or hand-edited files, test fixtures with broken
XML (truncated, XXE, bad UTF-8), `=>` in a CMP (§9.8), one ST routine with an unbalanced `END_IF`.

