# Siemens SCL — grammar notes

References:
- **TIA Portal information system**, "STEP 7 Professional: Programming with SCL". The topics named below are
  "Basics of SCL", "Operands", "Expressions" and "Creating an external source file" / "Structure of an external
  source file". I cite these topics by name. Version-specific chapter numbers are not given because they move between
  TIA versions. Points I have not checked against the text are marked *(verify)*.
- **S7-SCL V5.3 for S7-300/400 manual** (classic STEP 7, A5E00324650). It is used only for the classic constructs
  in §8.
- **IEC 61131-3:2013** for the shared core. See `codesys.md`: everything there that is not overridden here also
  applies to SCL.
- **Corpus evidence**: counts are files using the feature, out of 11,453 Siemens files (`.scl`, `.db`, `.udt`;
  2.9M lines, 393 repos). The survey script run on 2026-10-19 is not committed. Its numbers are in M4's plan and
  below.

Target: **TIA Portal external sources** (`.scl` with one or more blocks, `.db`, `.udt`). Classic STEP 7 SCL is
supported where it is cheap (§8). SIMATIC AX (`NAMESPACE`/`CLASS` ST, 5 files) is out of scope.

## 1. Lexical structure
| Item | Rule | Evidence |
|---|---|---|
| Case | Keywords and names are case-insensitive, **including quoted names** (decision 2026-10-19: `"Motor_DB"` = `"motor_db"`; TIA refuses names that differ only in case *(verify)*). The AST keeps the original spelling. | type names written `Int`, `Bool`, `Struct` everywhere |
| Quoted name | `"…"`, with any characters except `"` and newlines (spaces, `.`, umlauts, CJK). It is a **name**, never a string: `"Controll_vertikale Achse"`, `"PNIO_Diagnostic_Data"."2_AL1103"`. Token `QUOTED_IDENT`. It is defined in the SCL lexer before the core's `WSTRING_LITERAL`, so it wins the tie and SCL never produces `WSTRING_LITERAL`. The name is the text without the quotes; `""` escapes are not supported *(verify)*. | 3,221 files with `"x".y`; 262 with `:= "tag";` |
| Local prefix | `#name` / `#"name"` marks a block-local operand. `HASH identifier` at **parser** level, not a token: a `#NAME` token would break the typed literals `BOOL#TRUE` and `W#16#FF`. The builder checks that `#` and the name are adjacent. In the corpus, `#` followed by a space only occurs in comments. | 6,559 / 186 |
| Comments | `//` to end of line. `(* … *)` and `/* … */` are **not nested**: 17 files contain `(*(*text*)` followed by code, which nested lexing would swallow up to EOF. The SCL lexer overrides `BLOCK_COMMENT` and `C_COMMENT`. Whether `/* */` is accepted at all by TIA is *(verify)*, but it is harmless to accept. | `(*` 526, `/*` 381, nested-looking 17 |
| Attributes | `{ Key := 'Value'; Key2 := 'Value' }`. Uses the core `PRAGMA` token on the `PRAGMAS` channel. It appears after the block name, and **between a variable name and its `:`**: `AxisPos_max { ExternalWritable := 'False'} : LReal`. The builder turns each pair into one ATTRIBUTE pragma (`Pragma.siemensAttributes`) on the block or declaration. TIA writes `ExternalAccessible`/`ExternalVisible`/`ExternalWritable` only when they differ from the default (`'True'`), so a declaration *without* them is HMI-accessible. | 8,732 block-level; 3,224 in declarations |
| Integers | Core forms plus `B#16#81`, `W#16#0000`, `DW#16#…`, `LW#16#…`, `UDINT#9600`. These all parse as the core `typedLiteral` (`identifier '#' BASED_INTEGER`), so no new token is needed. | B# 126, W# 136, DW# 79 |
| Strings | `'…'` STRING with `$` escapes (`'$0D'`). `WSTRING#'…'`, `CHAR#'a'` and `WCHAR#'ä'` are typed literals. | WSTRING# 1,374 |
| Time | Core `T#`, `TIME#`, `LT#`, `LTIME#`, `TOD#`, `D#`, `DT#`, `LDT#`. Also `DTL#2262-04-11-23:47:16.854775807` (fraction with up to 9 digits) and `S5T#2H_46M_30S` / `S5TIME#…`. The SCL lexer adds `DTL` to `DT_LITERAL`'s prefixes and a new `S5TIME_LITERAL`. | DTL# 32, S5T# 9 |
| Direct address | Core `%I/%Q/%M` + size + address, plus `%DB<n>.DB<X|B|W|D><byte>[.<bit>]` (`%DB1.DBX0.0`), and the peripheral suffix `:P` (`%IW64:P`) *(verify; 0 corpus files)*. | %I/%Q/%M 70, %DB 7 |
| Slice | `.%X<n>`, `.%B<n>`, `.%W<n>`, `.%D<n>` after an operand: `#w.%X3`, `"DB".ConfigEPos.%X6`. Token `SLICE : '.%' [XBWD] DIGITS`, which avoids conflict with `.` + `DIRECT_ADDRESS`. The old form `.X0` (14 files) is ordinary member access, resolved as a bit in M4's semantic pass *(verify)*. | 391 |

## 2. Keywords
- **Reserved** (TIA SCL): block keywords `ORGANIZATION_BLOCK`, `FUNCTION_BLOCK`, `FUNCTION`, `DATA_BLOCK`, `TYPE`
  and their `END_*`; `BEGIN`; `REGION` / `END_REGION`; `GOTO`; plus the core statement and declaration keywords.
  `CONST` / `END_CONST` and `LABEL` / `END_LABEL` (classic) are tokens too.
- **Soft** (keyword tokens that `identifier` also accepts):
  - Header words `VERSION`, `AUTHOR`, `FAMILY`, `NAME`, `TITLE` (as a plain word, see §3), `KNOW_HOW_PROTECT`.
    Variables called `name` and `version` are common.
  - `DB_SPECIFIC`.
  - The CODESYS soft words from the core.
- Not tokens: `ENO`, `RET_VAL`, `VOID`, `VARIANT`, `DB_ANY`, `ANY`, `DTL`, `IEC_TIMER`. These are names, and the
  semantic builtins table resolves them.

## 3. Block structure ("Structure of an external source file")
```
FUNCTION_BLOCK "Name"            | FUNCTION "Name" : Void|Type     | ORGANIZATION_BLOCK "Main"
TITLE = free text to end of line
{ S7_Optimized_Access := 'TRUE' }
AUTHOR : word | 'string'
FAMILY : word | 'string'
NAME : word | 'string'
VERSION : 0.1 | '1.3'
KNOW_HOW_PROTECT
   VAR_INPUT … END_VAR  VAR_OUTPUT …  VAR_IN_OUT …  VAR [RETAIN|NON_RETAIN|CONSTANT|DB_SPECIFIC] …  VAR_TEMP …
   CONST … END_CONST  LABEL … END_LABEL                   (classic)
BEGIN
   statements
END_FUNCTION_BLOCK | END_FUNCTION | END_ORGANIZATION_BLOCK
```
- **Header attribute order**: TIA writes `TITLE`, `{…}`, `AUTHOR`, `FAMILY`, `NAME`, `VERSION`. The grammar takes them
  in any order (`blockAttribute*`), each at most once (the builder reports duplicates).
- **`TITLE = …`** runs to the end of the line, and the text can be bare (`TITLE = Batch I/O Signal Data Type`, 659
  files) or quoted (`"…"` or `'…'`, 334 files). It is one lexer token `TITLE_LINE`. That token is only produced when
  `TITLE` is the first word on its line and is followed by `=`, which a lexer predicate checks. The builder strips the
  surrounding quotes.
- **`AUTHOR`/`FAMILY`/`NAME`** are normal tokens: soft keyword, `:`, then a `STRING_LITERAL`, `identifier` or
  `INTEGER`. In a VAR section the same words are variable names (`name : String;`). The parser tells the two apart
  by position: header attributes come before the first VAR section. **`VERSION`** takes `REAL` (`0.1`, 9,723 files)
  or `STRING_LITERAL` (`'1.3'`).
- **FC result**: `FUNCTION "f" : Void` (2,800 files) or a type (1,911). The block is assigned its result through its
  own name, `#FC_ProveAdd := …`. The semantic model binds that to the result variable, as in CODESYS. A missing
  `: type` (2 files) is accepted and means `Void` *(verify)*.
- **`BEGIN`** separates declarations from code. It is required in TIA sources for code blocks (9,541 files). Without
  `BEGIN`, the body still parses (rule `pouBody` after the var sections), and the builder reports `PLC-SYN-…` "missing
  BEGIN" as a warning. I haven't seen TIA accept sources without it *(verify)*.
- **Instance names and numbers**: block names may be unquoted (`FUNCTION_BLOCK FB2800`, 671 files, mostly classic).
  `FB2800` is an ordinary identifier. Treating it as an absolute block number is left to the classic TODO.

### Data blocks
```
DATA_BLOCK "Name"
{ S7_Optimized_Access := 'FALSE' }
VERSION : 0.1
NON_RETAIN | RETAIN
   STRUCT … END_STRUCT;        // global DB with its own layout     (2,153)
 | "FB_Name"                   // instance DB of an FB              (466 incl. UDT-typed)
 | "UDT_Name"                  // DB typed by a UDT
   VAR … END_VAR               // also seen in global DBs            (verify)
BEGIN
   member := init;  arr[1] := 2;  "nested".x := TRUE;
END_DATA_BLOCK
```
- `"FB_Name"` and `"UDT_Name"` look the same in the syntax. The AST stores `basedOn` (a name), and the
  **semantic model** decides whether it is an FB (instance DB, a global FB instance) or a UDT (typed DB).
- The `BEGIN` part holds initial-value assignments, parsed as `statementList`. The builder keeps only assignments and
  reports anything else.
- Classic form: `DATA_BLOCK DB2800 FB2800` (44 files) is the same rule with unquoted names.

### User types
- TIA: `TYPE "Name" <attributes> STRUCT … END_STRUCT; END_TYPE` (1,926 files), one type per `TYPE`, with no `:`.
- IEC style: `TYPE Name : STRUCT … END_STRUCT; END_TYPE` (67 files) and several types per block. One rule covers
  both forms: `TYPE sclTypeItem+ END_TYPE`, where an item is `identifier ':'? blockAttribute* typeSpec (':=' init)? ';'?`.
- `END_STRUCT;` usually has a `;` (2,740 files), but 80 files omit it. Both are accepted.

## 4. Declarations
- **Sections**: `VAR_INPUT`, `VAR_OUTPUT`, `VAR_IN_OUT`, `VAR`, `VAR_TEMP`, `VAR CONSTANT`, `VAR RETAIN`,
  `VAR NON_RETAIN`, `VAR DB_SPECIFIC` (19 files, also `VAR_OUTPUT DB_SPECIFIC`). `VAR_STAT` (3 files) is accepted.
  Classic `CONST name := value; END_CONST` (47 files) becomes a `VAR CONSTANT` `VarBlock` whose types the semantic
  model infers from the values. Classic `LABEL a, b; END_LABEL` (3 files) is parsed and dropped: labels are defined
  by their use in the body.
- **Declaration**: `name [{attrs}] [AT other] : type [:= init];`.
  - `AT other` (112 files) is an **overlay view** of another local variable (`DateAndTimeNowCoporate AT DateAndTimeNow
    : STRUCT …`), not a direct address. It is stored as `VarDecl.overlay`.
  - SCL declarations take no `AT %…` *(verify; 0 corpus files)*. The core form stays accepted.
- **Types**:
  - The core forms. `String[20]` and `WString[100]` use the core `stringType`.
  - `Array[lo..hi, …] of T` and `Array[*] of T` (275 files).
  - Inline `Struct … END_STRUCT;` inside a declaration (980 files), and `Array[..] of Struct` (130 files).
  - `REF_TO T` (18 files).
  - `Variant` / `DB_ANY` / `ANY` / `POINTER` are names.
  - `"UDT"` / `"FB"` (quoted) as type names.
  - The system FB types `TON_TIME`, `IEC_TIMER` and so on are names too.
- **Initial values**: core forms. Array repetition `[3(0)]` is the same as in CODESYS.

## 5. Statements
- Core statements: assignment, call (`"FC"(…)`, `"inst_DB"(…)`, `#inst(…)`, `#res := "FC"(…)`), IF / CASE / FOR /
  WHILE / REPEAT, EXIT (624 files), CONTINUE (38), RETURN (2,525; no value).
- `?=` (13 files) is assignment attempt, as in the core. `REF_TO` deref uses `^` as in the core.
- **CASE**:
  - Labels are expressions, ranges or lists (`1..9:`), or local constants (`#S_IDLE:`).
  - `ELSE` may be followed by `:` (31 files). It is accepted and ignored.
- **`REGION [text]` … `END_REGION`** (1,459 files). The text runs to the end of the line and may contain `;` and
  quotes (`REGION Eixo Z (subir; descer)`), and it is empty in 22 files.
  - Lexer token `REGION_LINE`: `REGION` as the first word on its line, then optionally whitespace + rest of line. It
    is **not** produced when the rest begins with `:=`, `.`, `[` or `(` (in case of a variable named `region`, legal in
    classic SCL).
  - Regions nest and hold statements only. They become `Statement.Region(name, body)`.
- **`GOTO label;`** (71 files) with `label:` definitions in a statement list, never in a CASE body (same as
  CODESYS `JMP`). In the AST they are the existing `Jump`/`Label` nodes.
- **ENO**: `ENO := expr;` (423 files) assigns the implicit enable output. `ENO` is a name that the semantic model
  binds to an implicit BOOL output of FBs and FCs.
- **System FB call on an instance DB**: `"IEC_Timer_0_DB".TON(IN := …, PT := …)` (19 files). It parses as member
  call. In the semantic model, `TON` / `TOF` / `TP` / `TONR` / `CTU` / `CTD` / `CTUD` on an `IEC_TIMER` /
  `IEC_COUNTER` (or unresolved) instance become a call of that system FB on the instance *(verify)*.

## 6. Expressions
Core precedence (TIA "Expressions": `**`, unary, `* / MOD`, `+ -`, comparisons, `= <>`, `AND &`, `XOR`, `OR` —
the same order as IEC). The operands that differ from CODESYS are the quoted and `#` names, slices, `%DB` addresses
and the `S5T#`/`DTL#` literals. `AND_THEN` / `OR_ELSE` do not exist in SCL; they stay tokens of the shared core and are
never produced by SCL code.

## 7. Error recovery
- The sync set adds `BEGIN`, `END_REGION`, `END_ORGANIZATION_BLOCK`, `END_DATA_BLOCK`, `END_CONST` and `END_LABEL`
  to the core's `;`, `END_*` set.
- It also adds `THEN`, `DO` and `OF`, so a broken condition (`IF a > THEN`) never deletes the keyword that ends it.
  Without them, single-token deletion removed `THEN` and the next line became the right operand.
- `compilationUnit` is `(libraryElement | strayToken)* EOF`. ANTLR does not resynchronise a `libraryElement*` loop
  while it is still in error-recovery mode (`DefaultErrorStrategy.sync` returns early), so a block broken beyond repair
  would otherwise cost every block after it. Stray tokens get one `PLC-SCL-006` per run.
- The CODESYS grammar has both weaknesses too; fixing it there is a follow-up because it moves the M1 baselines.

## 8. Classic STEP 7 SCL (cheap support; TODO in M4 plan for more)
| Construct | M4 | Evidence |
|---|---|---|
| `CONST … END_CONST`, `LABEL … END_LABEL` | parsed | 47 / 3 |
| unquoted block names `FB10`, `DB10` | parsed as names, not linked to block numbers | 671 |
| `DB10.DBW2`, `DB10.DX0.1` absolute DB access | parsed as member access. The **builder** recognises the `DB<n>.DB?[XBWD]<n>` shape and turns it into a `DirectAddress` *(verify shapes against the V5.3 manual)* | 23 |
| bare `M10.0`, `IW2`, `I0.0` | **not** recognised. They lex as identifiers and stay unresolved names | — |
| `P#DB1.DBX0.0 BYTE 10` ANY pointer | syntax error with recovery, reported in the corpus check | 12 |
| `S5T#` | parsed | 9 |

## 9. Known gaps (deliberate, M4)
- TIA Openness XML (SimaticML: LAD/FBD/STL networks, tag tables). PLC tags stay `ExternalTag` symbols.
- STL/AWL, SIMATIC AX.
- Know-how-protected sources (encrypted body): a diagnostic, no AST for the body.
