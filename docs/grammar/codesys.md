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
| Generic constants (TwinCAT 3.1.4026) | `VAR_GENERIC CONSTANT … END_VAR`, `fb : FB_Buffer<10>;` | CODESYS help "VAR_GENERIC CONSTANT". The section comes before EXTENDS / IMPLEMENTS: `FUNCTION_BLOCK F VAR_GENERIC CONSTANT … END_VAR IMPLEMENTS I`, `… END_VAR EXTENDS FB_Base<n>`. Clauses after any other section are an error (`PLC-SYN-001`, still kept in the AST). Generic arguments of a base are not kept in `Pou.extendsTypes`, and the section is a `VAR CONSTANT` block in the AST |
| Chained assignment | `a := b := FALSE;`, `a R= b R= c;` | AST: one `Assignment` per target, same value |
| ExST: assignment as an expression (M12) | `IF b := (i = 1) THEN`, `IF (n := FIND(s, 'x')) > 0 THEN`, `RETURN(bInit := t <= 0);`, `F := (x := x + 1);` | CODESYS online help "Extended Structured Text (ExST)" *(verify the exact rules)*. Accepted in two places only: inside parentheses and as a whole `IF`/`ELSIF`/`WHILE`/`UNTIL` condition. The value is the rest of the expression (lowest precedence); the target must be a variable (`PLC-SYN-001` otherwise). AST `Expression.Assign(target, value)`: writes `target`, yields `value`; the semantic model records the write on the enclosing statement. Corpus 2026-10-25: ~50 files in ~25 TwinCAT repos. Statement-level `a := b := c` stays a chained assignment |
| Qualifier after `STRUCT` (M12) | `STRUCT PERSISTENT`, `STRUCT RETAIN`, `STRUCT CONSTANT` | *(verify)*. `CONSTANT`, `RETAIN`, `NON_RETAIN`, `PERSISTENT`; parsed, not kept in the AST (`TypeRef.StructType` has no qualifiers yet, as for `TYPE INTERNAL`). Corpus 2026-10-25: 4 TwinCAT repos |
| Comma after the last enum value (M12) | `(Load := 1, CameraCheck := 2, );`, `(IDLE := 0, RUNNING := 1, ) WORD;` | *(verify)*. The comma is dropped; the AST is the same as without it. Corpus 2026-10-25: 3 TwinCAT repos (one a production SLAC project) |
| Transition written as its condition, ending with `;` (M12) | `<Transition>` body `(bMoto OR bAuto) AND nStep = 1;`, `bStart;` | *(verify)*. A transition object whose ST body has no `:=` and at most one `;`, at its end, is its condition (rule `transitionCondition`); the AST is `Trans := <condition>;` as for the form without `;`. Before M12 the `;` form was parsed as statements: a syntax error, or `bStart;` read as a call. Corpus 2026-10-25: 34 transitions in 2 TwinCAT repos (80 bare-condition transitions in 8 repos in all) |
| `REF=` in declarations | `r : REFERENCE TO T REF= x;` | `VarDecl.referenceInit` |
| Global scope operator | `.gVar` | `NameRef.globalScope` |
| Type operands | `SIZEOF(POINTER TO T)`, `SIZEOF(STRING)` | `Expression.TypeOperand` |
| `10#` literals | `10#9` | not IEC |
| Multi-line string literals | `s := 'line1⏎line2';` | so an unterminated string runs to the end of the text |
| Pragmas spanning lines / unbalanced quotes | `{attribute 'pytmc' := '⏎ pv: X⏎'}` | a pragma ends at the first `}` |
| Stray `;` | `x : INT;;`, `END_TYPE;`, `FUNCTION_BLOCK X;` | |
| Modifiers on PROGRAM / INTERFACE / accessors | `PROGRAM INTERNAL MAIN`, `INTERFACE PUBLIC I`, `PROTECTED VAR … END_VAR` (Set accessor) | |
| Modifiers on TYPE (M12) | `TYPE INTERNAL E_State : (...); END_TYPE` | library-internal DUTs; 29 files in 10 TwinCAT repos (2026-10-25). Not kept in the AST *(verify the allowed set; the grammar takes any POU modifier)* |
| Empty structured initialiser (M12) | `fbList : FB_List := ();`, `[15(())]` | all members at their defaults; `Initializer.StructInit` with no fields. ~17 TwinCAT repos. CODESYS grammar only |
| `VAR PUBLIC` / `VAR PRIVATE` | IEC Ed. 3 classes | `VarBlock.Qualifier` |
| `FOR` over non-simple variables | `FOR a[0] := …`, `FOR gvl.i := …` | `For.variable` is an expression |
| `RETURN expr;` | | *(verify)*; not IEC |
| Trailing comma in calls | `f(a := 1, b := 2,)` | |
| Missing `END_*` at end of text | CODESYS text exports (declaration + implementation, no `END_FUNCTION_BLOCK`) | |
| Operators as functions | `AND(a, b)`, `OR(…)`, `XOR(…)`, `MOD(…)` | generated by OpenPLC/MATIEC |
| Bare variable as statement | `bSignal;` | **warning** `PLC-SYN-006` (no effect), not an error |

## 8. Known gaps (deliberate, M1)
Measured on the corpus (`./gradlew :plc-devtools:corpusCheck`): TwinCAT POU/DUT/GVL/Itf are 98.7–99.8% error-free
(repo-weighted). Most of what remains, and most `.st` failures, falls into these groups:
- **Other dialects in `.st` files**: Siemens SCL (`#local`, `"quoted"`; → M4), IEC textual SFC (`STEP`/`TRANSITION`),
  tool-specific test dialects (`TEST_PROGRAM`, `ASSERT_*`), proprietary blocks (`END_NATIVE`).
- ~~CODESYS ExST~~: accepted since M12 in parentheses and as a whole condition (§7). Not accepted elsewhere
  (`a := NOT b := c;`, chains inside parentheses `(a := b := c)`): none in the corpus.
- **Generated or broken files**: LLM output with `? :` or `{}`, double-escaped `&gt;` inside CDATA, merge-conflict
  markers, truncated XML.
- Conditional pragmas are not evaluated; both branches are parsed.
- IL (Instruction List) bodies: see §10 (M11).
- TwinCAT graphical bodies (NWL, CFC, SFC): read since M11, see §9. `StateChart` (7 bodies) and the TwinCAT 2
  `LADDER`/`FBD` forms (6) still give `PLC-TC-001`.

## 9. TwinCAT graphical bodies (M11)
Beckhoff does not document these formats; everything below is read from the corpus (2026-10-22: 1,975 NWL bodies in
130 repos, 418 CFC in 36, 196 SFC in 33). Each guess is marked `TODO(verify-format)` in the code; exports from a
TwinCAT XAE or CODESYS install (free) would turn them into tests.

### 9.1 `XmlArchive`
`<Implementation><NWL|CFC|SFC>` holds `<XmlArchive><Data><o t="…ImplementationObject">…</o></Data><TypeList/>`, a
serialised .NET object graph (`kit/ArchiveReader`, one body at a time with file offsets):

| Element | Meaning |
|---|---|
| `<o n="Member" t="Type">` | an object; `t` is a name from `TypeList` (a .NET type or a GUID) |
| `<v n="Member" t="…">` | a value: strings quoted (`"IO.Switch"`, no escaping of `\`; XML entities for `<`, `>`, `&`), numbers (`12`, `70L`), booleans, enum names |
| `<l2 n="Member" cet="Type">` | a list; items are `<o>` (type `cet` unless they have their own `t`), `<v>` or `<n/>` |
| `<n n="Member"/>` | null |
| `<d2 …>` | a dictionary (only CFC routing paths; ignored) |

Expressions (operands, transition conditions, CFC texts) are the quoted string's characters, parsed as ST with spans in
the `.TcPOU` file.

### 9.2 NWL: LD and FBD networks
`NWLImplementationObject` with `DefaultViewMode` (`Ld` 1,416 / `Fbd` 558) and `NetworkList` of `Network` objects
(`Title`, `Comment`, `Label`, `OutCommented`, `ILActive`, `NetworkItems`). A network is a list of **box trees**
(the CODESYS LD/FBD/IL editor model):

| Object | Meaning |
|---|---|
| `BoxTreeAssign` | `OutputItems` (list of `Operand`) are written from `RValue` (a tree) |
| `BoxTreeBox` | a box: `BoxType` (`AND`, `TON`, `FB_X`), `CallType` (`And`, `Or`, `Eq`, `Move`, `FunctionBlock`, `Function`, `Program`, `Action`, `Method`, `Conversion`, …), `Instance` (operand), `InputItems` (trees), `InputParam/OutputParam` (`Names`, `Types`), `OutputItems` (operands written from the box's outputs, `<n/>` where none), `EN`, `ENO` |
| `BoxTreeOperand` | one `Operand` (`Operand` text, `Type`, `Flags`) |
| `BoxTreeParallel` | `Trees` in parallel (OR), after its optional `Input` in series; `Mode` `BoxShortCircuit` / `Sequential` |
| `BoxTreeDemux` | a shared value: defined once with `Input` and `VarId`, used elsewhere by `VarId` without input |
| `BoxTreeTerminator` | an open input: the rail in LD (TRUE), unconnected in FBD |
| `STSnippet` (49) | ST inside a box: not read |

- When `EN` is set, the first input item and the first `InputParam` name are `EN`.
- Operand `Flags` (`TODO(verify-format)`): on a contact/input **1** negated, **16** rising edge, **32** falling edge;
  on an output **1** negated coil, **2** set, **3** reset, **4** jump to the label named by the operand, **8** return.
  Evidence: the same coil appears with 2 and 3 in one POU (set/reset); 4 is always a network label; 8 operands are the
  placeholder `???`.
- `Flags` on `BoxTreeAssign` (16, 32; 17 cases) are not interpreted.
- **Mapping to `Network`**: in **LD** view an AND box is contacts in series, an OR box or parallel is parallel
  branches, a BOOL operand on the rung is a contact, assignment outputs are coils, and a box takes the power flow on
  its first input (EN when set). Non-BOOL operands are input variables. In **FBD** view every box is a block (AND/OR
  included), operands are input variables, outputs are output variables (set/reset ones coils). An edge on a data
  input is a contact from the rail. `Program`/`Action`/`Method` boxes are called by their `BoxType`.
- Networks have no coordinates (viewers lay them out); elements are executed in tree order (post-order), which is the
  editor's left-to-right order. Commented-out networks (`OutCommented`) are skipped.

### 9.3 CFC
`CFCImplementationObject` with `Items/InnerList`: elements with `Id`, `Bounds="x, y, w, h"` (grid units, ×10 for
drawings) and pins with their own `Id`; `CFCConnection` objects join `SourcePinId` → `DestPinId`.

| Object | Pins | Meaning |
|---|---|---|
| `CFCInputElement` | `Output` | read of `Text` |
| `CFCOutputElement` | `Input` (`CFCInputPinWithSetReset`: `Negated`, `SetReset` None/Set/Reset) | write of `Text` |
| `CFCBoxElement` | `Inputs`, `Outputs` (`Negated`) | `KindOfCall` (FunctionBlock, Operator, Function, Program, Method, LocalMethod), `EnEno`; `Texts` = input pin names, output pin names, then the **type**, then the **instance** |
| `CFCJumpElement` / `CFCLabelElement` / `CFCReturnElement` | | control flow (`Text` = label) |
| `CFCCommentElement`, `CFCPage` | | not semantic |
| `CFCComposerElement` / `CFCSelectorElement` (38) | | struct compose/select: not read (info diagnostic) |

- Text order evidence: `SR`/`fbSR`; `GF_Warten`/`Kommunikation.Warten` (an instance in a GVL); operators have an empty
  instance.
- **Execution order**: no number is stored; the item list order is used (`TODO(verify-format)`: CODESYS keeps the
  list in execution order). One CFC body is one network.

### 9.4 SFC
`SFCImplementationObject/Root` is an `SFCSegment`; a segment's `ElementList/InnerList` is a sequence of `SFCStep`,
`SFCTransition`, `SFCBranch` (whose own list holds one `SFCSegment` per branch), `SFCJump` and `SFCMacro`. Properties
are `Attributes`: `SFCAttribute` objects with an `AttributeId` GUID and a `Value` (`StringValue`, `BoolValue`, …):

| GUID | On | Meaning (from the values) |
|---|---|---|
| `38391c6d-…` | all | name: step name, transition condition (or transition name), action name, jump target |
| `7d894980-…` | all | comment |
| `6844a48e-…` | step | initial step |
| `a6b08bd8-…` / `700a583f-…` / `a2621e18-…` | step | entry / active / exit action (names like `X_entry`, `_aX_active`) |
| `cacf1a68-…` / `b693554c-…` | step | minimum / maximum time (`T#2s`; not used) |
| `e174fc0d-…` | action association | qualifier, with a duration after a space (`D T#1s`) |
| `23bdaa98-…` | branch | TRUE for a simultaneous branch (30 of 269) |

- **Sequence semantics**: a step after a transition is its target; a transition after a step leaves it. An alternative
  branch follows a step and each of its segments starts with a transition; a simultaneous branch follows a transition
  and each segment starts with a step (this decides the branch kind; the attribute is only a hint). After a
  simultaneous branch, the next transition leaves the last step of every segment. A jump ends a sequence. A
  transition left open at the end of the main sequence goes to the initial step.
- Actions: an association naming an `<Action>` of the POU is a reference (called while active, per qualifier); one
  naming a declared variable sets it TRUE; anything else (`fbInstance.Action`) is called. Entry/active/exit actions are
  `P1`/`N`/`P0`.
- Transition conditions naming a `<Transition>` of the POU refer to it; otherwise they are ST expressions. A
  `<Transition>` member whose ST is a bare expression (`NOT fb.BUSY`) means `Name := NOT fb.BUSY;`.
- Lowered by the shared `SfcLowering` (plcopen.md §6) with the implicit step variables `S.X`/`S.T`. `SFCMacro` (6)
  is read as a plain step with an info diagnostic. No coordinates: the drawing is laid out by the viewer.


## 10. Instruction List (M11)
IEC 61131-3 2nd ed. §3.2 (IL was deprecated in the 3rd edition and removed in the 4th). In the corpus: 21 PLCopen
`<IL>` bodies in 16 repos and IL POUs in 15 `.st` files in 9 repos (some written with a `;` after each instruction);
no TwinCAT IL. Read by `kit/IlTranslator` for both.

- **Scanning**: one instruction per line, `[label:] operator[N][C][(] [operand] [;]`, comments `(* *)` and `//`. A
  `CAL fb(` with formal arguments may continue over lines until its parentheses close. A line `)` closes a deferred
  operation. No grammar file: the line shape is fixed, and the operands are ST expressions, parsed by the CODESYS ST
  grammar (entry `expressionOnly`) with spans in the file. Unknown lines give `PLC-IL-001` (warning) and are skipped.
- **Lowering** by following the current result (CR) as an expression:

| IL | ST |
|---|---|
| `LD x` / `LDN x` | CR := `x` / `NOT x` |
| `ST y` / `STN y` | `y := CR;` / `y := NOT CR;` |
| `S y` / `R y` | `IF CR THEN y := TRUE;` / `… FALSE;` |
| `AND x`, `ANDN x`, `&`, `OR`, `XOR`, `ADD` … `LT` | CR := `CR op x` (`N`: `NOT x`) |
| `op( x … )` | deferred: CR := `CR op (x …)` at the `)` |
| `NOT` | CR := `NOT CR` |
| `JMP L` / `JMPC L` / `JMPCN L` | `JMP L;` / `IF CR THEN JMP L;` / `IF NOT CR …` |
| `CAL fb(…)` / `CALC` / `CALCN` | `fb(…);`, conditional as above |
| `RET` / `RETC` / `RETCN` | `RETURN;`, conditional |
| `S1 fb`, `R1`, `CLK`, `CU`, `CD`, `PV`, `IN`, `PT` | `fb.S1 := CR;` |
| `F a, b` (any other name) | CR := `F(CR, a, b)` |

- Where the CR crosses a label or a jump (another path may arrive with its own CR), it is stored in the synthetic
  variable `cr` first and read back from it after the label.
- **`.st` files**: a POU whose body (after its last `END_VAR`) has at least four IL lines for every other line is read
  as declarations (ST grammar) plus IL; everything else in the file is parsed as ST as before.
