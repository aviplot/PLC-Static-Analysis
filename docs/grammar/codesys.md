# CODESYS / TwinCAT Structured Text — grammar notes

References:
- **IEC 61131-3:2013 (Ed. 3.0)**. Clause numbers below are cited at clause level (6.x, 7.3). Finer sub-clause numbers
  are marked *(verify)*: I wrote them from memory of the table of contents and have not checked them against the text.
- **CODESYS Online Help** (V3.5) topics named in each entry. TwinCAT 3 inherits CODESYS V3 ST and shares those topics.
- **Corpus evidence**: percentages are the share of files using a feature. They come from the P0 harvest
  (46.9k `.st` files, 46k TwinCAT `.TcPOU/.TcDUT/.TcGVL/.TcIO` files).

## 1. Lexical structure (IEC 6.1, 6.2, 6.3)
| Item | Rule | Evidence |
|---|---|---|
| Case | Keywords and identifiers are case-insensitive → lexer `caseInsensitive = true`. The AST keeps the original spelling, and names compare case-insensitively. | — |
| Identifiers | `[A-Za-z_][A-Za-z0-9_]*`. CODESYS allows a leading `_` and repeated `__` (IEC forbids double underscore), so we accept both. Non-ASCII letters (e.g. `ä`) are accepted in identifiers as a lexer extension: real German/Chinese code uses them, and CODESYS warns rather than rejects. | B&R `_INIT`, `_CYCLIC` |
| Comments | `(* … *)` **nested**, `/* … */` (nested in CODESYS), `// …` to end of line → channel `COMMENTS`. An unterminated block comment runs to EOF and gets a diagnostic. | nested 0.9% / 0.6%; `/*` 1.5%; `//` 23% / 56% |
| Pragmas | `{ … }` → channel `PRAGMAS`. Kinds: `{attribute 'name' [:= 'value']}`, `{warning/info/error/text '…'}`, conditional `{IF defined(...)} {ELSIF} {ELSE} {END_IF}`, `{region "…"}`/`{endregion}`, `{library …}`, `{flag …}`. CODESYS help: "Pragmas". Pragma text is classified in the AST builder, not the lexer. | attribute 28% of TwinCAT files |
| Integer | `123`, `1_000`, `2#1010_1010`, `8#17`, `16#FF_FF`. Sign belongs to the expression (unary minus); in typed literals it is part of the literal. IEC 6.3.2 *(verify)*. | — |
| Real | `1.5`, `1.5E-3`, `1E5` (CODESYS accepts an exponent without a fraction). Must not swallow `..`: `1..10` lexes as INT `..` INT. | — |
| Typed literal | `INT#5`, `REAL#1.0`, `WORD#16#FF`, `BOOL#TRUE`, enum `E_Color#Red`. Parsed as `typeName '#' literal-or-identifier`; `#` is its own token. | `E#Val` 2.5% of `.st` |
| Duration | `T#1h2m3s4ms`, `TIME#…`, `LT#…`/`LTIME#…`, with `_`, fractions and a leading `-`. One lexer token. IEC 6.3.4 *(verify)*. | — |
| Date/time | `D#2024-01-31`, `DATE#`, `LD#`; `TOD#12:00:00.5`, `TIME_OF_DAY#`, `LTOD#`; `DT#2024-01-31-12:00:00`, `DATE_AND_TIME#`, `LDT#`. One token each. IEC 6.3.5 *(verify)*. | — |
| Strings | `'…'` (STRING, escapes `$$ $' $L $N $P $R $T $hh`); `"…"` (WSTRING, `$"`, `$hhhh`). No raw newlines. Unterminated → token to end of line + diagnostic. | `"…"` 14% |
| Direct address | `%I`, `%Q`, `%M` + optional size `X B W D L` + `*` or `n(.n)*`: `%IX0.1`, `%QW10`, `%MD100`, `%I*`. IEC 6.5.5 *(verify)*. | `AT %` 10% / 6% |
| Operators | `:= => ?= ^ ** * / + - & = <> < <= > >= . .. , ; : ( ) [ ] #`. Word operators: `MOD NOT AND OR XOR AND_THEN OR_ELSE`. `S=`, `R=` and `REF=` are **not** tokens (see §5). | — |

## 2. Reserved and soft keywords
- **Reserved** (cannot be identifiers, CODESYS-compatible): block keywords (`PROGRAM … END_PROGRAM`, `FUNCTION_BLOCK`,
  `FUNCTION`, `METHOD`, `PROPERTY`, `INTERFACE`, `TYPE`, `STRUCT`, `UNION`, `VAR*`, `END_*`), statements (`IF THEN ELSIF
  ELSE CASE OF FOR TO BY DO WHILE REPEAT UNTIL EXIT CONTINUE RETURN JMP`), `AT`, `ARRAY`, `POINTER`, `REFERENCE`,
  `STRING`, `WSTRING`, `TRUE`, `FALSE`, operators, `EXTENDS`, `IMPLEMENTS`, `THIS`, `SUPER`, `ABSTRACT`, `FINAL`,
  `PUBLIC`, `PRIVATE`, `PROTECTED`, `INTERNAL`, `CONSTANT`, `RETAIN`, `PERSISTENT`, `NON_RETAIN`.
- **Soft** (keyword tokens that `identifier` also accepts): IEC-only words CODESYS does not reserve, or that the
  corpus uses as names: `CLASS`, `NAMESPACE`, `USING`, `CONFIGURATION`, `RESOURCE`, `TASK`, `ON`, `WITH`, `INTERVAL`,
  `PRIORITY`, `SINGLE`, `STEP`, `TRANSITION`, `ACTION`, `REF_TO`, `REF`, `OVERRIDE`, `READ_ONLY`, `READ_WRITE`, `GET`,
  `SET`, `VAR_ACCESS`. Elementary type names (`INT`, `BOOL`, `TIME`, …) are ordinary identifiers. Type resolution
  happens in M2, so no keyword list has to track vendor types (`__UXINT`, `LWORD`, `XWORD`, …).

## 3. Declarations (IEC 6.4, 6.5)
- Variable sections: `VAR`, `VAR_INPUT`, `VAR_OUTPUT`, `VAR_IN_OUT`, `VAR_GLOBAL`, `VAR_EXTERNAL`, `VAR_TEMP`, `VAR_STAT`
  (CODESYS), `VAR_INST` (CODESYS, methods), `VAR_CONFIG`, `VAR_ACCESS`. Qualifiers: `CONSTANT`, `RETAIN`, `NON_RETAIN`,
  `PERSISTENT` (CODESYS), `RETAIN PERSISTENT`. Each section is closed by `END_VAR`.
- Declaration: `a, b [AT %addr] : type [:= init];`. Init values are an expression, an array initialiser
  `[1, 2, 3(0)]` (repeat count), or a struct initialiser `(x := 1, y := 2)`. FB instance inits use the same shape:
  `fbT : TON := (PT := T#1s);`.
- Types:
  - `ARRAY [lo..hi, …] OF t`, with `ARRAY [*] OF t` for variable-length in/out (CODESYS).
  - `POINTER TO t`, `REFERENCE TO t` (CODESYS), `REF_TO t` (IEC 6.4.4.10 *(verify)*).
  - `STRING[(n)]` / `STRING[[n]]`, `WSTRING(n)`.
  - Subrange `INT(0..100)`.
  - Inline enum `(a, b := 5)` and inline `STRUCT … END_STRUCT`.
  - Qualified names: `Tc2_Standard.TON`.
- `TYPE` blocks:
  - `STRUCT [EXTENDS base] … END_STRUCT`
  - `UNION … END_UNION` (CODESYS)
  - enum `(a := 0, b) [baseType] [:= default]` (base type after `)`: 2% of TwinCAT DUTs)
  - alias `T : OtherType;`
  - subrange.
  - Several declarations per `TYPE` block are allowed.
- Pragmas before a declaration attach to it (`{attribute 'qualified_only'}` before `VAR_GLOBAL`,
  `{attribute 'hide'}` before a variable).

## 4. POUs and OOP (IEC 6.6, CODESYS help "Object-oriented programming")
- `PROGRAM`, `FUNCTION name : type`, `FUNCTION_BLOCK [ABSTRACT|FINAL] [access] name [EXTENDS b] [IMPLEMENTS i, j]`.
- `METHOD [access] [ABSTRACT|FINAL] name [: type] … END_METHOD`. In `.st` text, methods appear inside the FB before
  `END_FUNCTION_BLOCK` (IEC style), or after it (some exporters). Both are accepted. (Methods inside an FB, in the same
  file: 1.3% of `.st` files.)
- `PROPERTY [access] name : type` with `GET … END_GET` / `SET … END_SET` in text form. In TwinCAT XML, `<Get>`/`<Set>`
  are separate elements.
- `INTERFACE name [EXTENDS a, b]` with method and property prototypes.
- `ACTION name: … END_ACTION`.
- IEC `CLASS … END_CLASS`, `NAMESPACE … END_NAMESPACE` and `USING` are present in the corpus (2–3% of `.st`), mostly
  from IEC conformance suites, so they are parsed too.
- `THIS^`, `SUPER^` (pointer-style deref on the instance).

## 5. Statements and expressions (IEC 7.3)
- Assignments:
  - `:=`
  - set/reset `x S= c;`, `x R= c;` (CODESYS)
  - reference assignment `r REF= x;` (CODESYS, 4% of TwinCAT)
  - assignment attempt `?=` (IEC).
  - `S=`/`R=`/`REF=` are matched as `identifier '='` after an assignable target, with a predicate on the identifier
    text. A statement cannot start with `expr =`, so this is unambiguous.
- Calls: `f(a, b)`, `fb(IN := x, Q => y)`, negated output `NOT Q => y`, method calls `a.b().c`, and call statements
  `fbTimer();`.
- Control flow:
  - `IF/ELSIF/ELSE/END_IF`
  - `CASE x OF 1, 2..5, E#A: … ELSE … END_CASE`
  - `FOR i := a TO b [BY c] DO … END_FOR`
  - `WHILE`, `REPEAT … UNTIL … END_REPEAT`
  - `EXIT`, `CONTINUE`, `RETURN`
  - `JMP label;` with labels `label:` (CODESYS)
  - `__TRY/__CATCH(e)/__FINALLY/__ENDTRY` (CODESYS).
  - A `;` after `END_IF` etc. is an empty statement (63% of `.st` files write it).
- Precedence, from IEC 7.3.2 *(verify)*, lowest to highest:
  1. `OR` / `OR_ELSE`
  2. `XOR`
  3. `AND` / `&` / `AND_THEN`
  4. comparisons `= <>`
  5. `< > <= >=`
  6. `+ -`
  7. `* / MOD`
  8. `**`
  9. unary `- + NOT`
  10. postfix (call, index, member, `^`)

  `**` is left-associative per IEC. CODESYS evaluates it left to right too *(verify)*.
- `__NEW(T [, n])`, `__DELETE`, `__QUERYINTERFACE`, `SIZEOF`, `ADR`, `REF` are ordinary calls. Their meaning is resolved in M2.

## 6. TwinCAT XML (`.TcPOU/.TcDUT/.TcGVL/.TcIO`)
Structure observed in the corpus (`TcPlcObject Version="1.1.0.1"`; `ProductVersion` from 3.1.4022 to 3.1.4026):
```
TcPlcObject
  POU(Name, Id, SpecialFunc)
    Declaration      CDATA: header + VAR blocks, no END_FUNCTION_BLOCK
    Implementation   ST (CDATA statements) | NWL | CFC | SFC | FBD | StateChart (graphical: not parsed in M1)
    Method(Name, Id, FolderPath)      Declaration + Implementation
    Property(Name, Id)  Declaration + Get(Name)/Set(Name), each Declaration + Implementation
    Action(Name)        Implementation
    Transition(Name)    Implementation
    LineIds             (IDE line mapping; ignored)
  DUT(Name)   Declaration (TYPE … END_TYPE)
  GVL(Name)   Declaration (VAR_GLOBAL … END_VAR)
  Itf(Name)   Declaration + Method/Property prototypes
```
- Each `<Declaration>`/`<ST>` becomes its own `SourceText` fragment, so spans resolve to the line in the `.TcPOU` file
  (which is what TwinCAT shows).
- Graphical implementations get an INFO diagnostic `PLC-TC-001` and an empty body flagged `unparsedBody`.
- `// TODO(verify-format)`: `<Implementation>` children other than `ST` that also embed ST (e.g. SFC step actions inside
  `XmlArchive`) are not extracted in M1.

## 7. Accepted beyond the spec (learned from the corpus)
Each of these appears in real, compiling TwinCAT/CODESYS projects of the P0 corpus. They are accepted without a
diagnostic unless noted. Items marked *(verify)* are accepted because the code compiles in the source project, but
the vendor documentation was not checked.

| Construct | Example | Notes |
|---|---|---|
| FB_init constructor arguments | `fb : FB_X(1, b := 2);`, `ARRAY[1..2] OF FB_X[(a:=1), (a:=2)]` | CODESYS help "FB_init" |
| Generic constants (TwinCAT 3.1.4026) | `VAR_GENERIC CONSTANT … END_VAR`, `fb : FB_Buffer<10>;` | *(verify)* exact syntax |
| Chained assignment | `a := b := FALSE;`, `a R= b R= c;` | AST: one `Assignment` per target, same value |
| `REF=` in declarations | `r : REFERENCE TO T REF= x;` | `VarDecl.referenceInit` |
| Global scope operator | `.gVar` | `NameRef.globalScope` |
| Type operands | `SIZEOF(POINTER TO T)`, `SIZEOF(STRING)` | `Expression.TypeOperand` |
| `10#` literals | `10#9` | not IEC |
| Multi-line string literals | `s := 'line1⏎line2';` | so an unterminated string runs to the end of the text |
| Pragmas spanning lines / unbalanced quotes | `{attribute 'pytmc' := '⏎ pv: X⏎'}` | a pragma ends at the first `}` |
| Stray `;` | `x : INT;;`, `END_TYPE;`, `FUNCTION_BLOCK X;` | |
| Modifiers on PROGRAM / INTERFACE / accessors | `PROGRAM INTERNAL MAIN`, `INTERFACE PUBLIC I`, `PROTECTED VAR … END_VAR` (Set accessor) | |
| `VAR PUBLIC` / `VAR PRIVATE` | IEC Ed. 3 classes | `VarBlock.Qualifier` |
| `FOR` over non-simple variables | `FOR a[0] := …`, `FOR gvl.i := …` | `For.variable` is an expression |
| `RETURN expr;` | | *(verify)*; not IEC |
| Trailing comma in calls | `f(a := 1, b := 2,)` | |
| Missing `END_*` at end of text | CODESYS text exports (declaration + implementation, no `END_FUNCTION_BLOCK`) | |
| Operators as functions | `AND(a, b)`, `OR(…)`, `XOR(…)`, `MOD(…)` | generated by OpenPLC/MATIEC |
| Bare variable as statement | `bSignal;` | **warning** `PLC-SYN-006` (no effect), not an error |

## 8. Known gaps (deliberate, M1)
Measured on the corpus (`./gradlew :frontend-st:corpusCheck`): TwinCAT POU/DUT/GVL/Itf are 98.7–99.8% error-free
(repo-weighted). Most of what remains, and most `.st` failures, falls into these groups:
- **Other dialects in `.st` files**: Siemens SCL (`#local`, `"quoted"`; → M4), IEC textual SFC (`STEP`/`TRANSITION`),
  tool-specific test dialects (`TEST_PROGRAM`, `ASSERT_*`), proprietary blocks (`END_NATIVE`).
- **CODESYS ExST** (assignment as an expression: `IF b := (i = 1) THEN`). It conflicts with chained assignment in the
  grammar, so it is not supported; it is rare (one teaching repo).
- **Generated or broken files**: LLM output with `? :` or `{}`, double-escaped `&gt;` inside CDATA, merge-conflict
  markers, truncated XML.
- Conditional pragmas are not evaluated; both branches are parsed.
- IL (Instruction List) bodies are rejected with a syntax diagnostic.
- `// TODO(verify-format)`: TwinCAT `<Implementation>` children other than `ST` that embed ST text (SFC step actions
  inside `XmlArchive`) are not extracted.
