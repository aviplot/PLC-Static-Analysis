# PLCopen XML (TC6) — format notes

References:
- **PLCopen TC6 XML, "XML Formats for IEC 61131-3", version 2.01** (2009) with its XSD `tc6_xml_v201.xsd`, and the 2.0
  schema (`tc6_0200`, the namespace CODESYS and Beremiz still write). Cited as *TC6*. The 2.0 and 2.01 element sets
  used here are the same; differences found in the corpus are noted inline.
- **IEC 61131-3:2013** for the semantics of LD (§8.2), FBD (§8.3: evaluation order, feedback) and SFC (§6.7).
- **Corpus evidence**: 2,827 files, 438 repos (survey 2026-10-19, not committed): CODESYS ~1,500 files / ~290 repos,
  TwinCAT ~300 / 50, Beremiz/OpenPLC ~300 / 30, small numbers from ctrlX, Machine Expert, Delta, Phoenix.

Points not checked against the TC6 text or a producer's documentation are marked `TODO(verify-format)` (§9).

## 1. Container
```
project (xmlns="http://www.plcopen.org/xml/tc6_0201" | tc6_0200)
  fileHeader companyName productName productVersion creationDateTime
  contentHeader name … (coordinateInfo, addData)
  types
    dataTypes/dataType name → baseType → <type element>
    pous/pou name pouType=program|functionBlock|function
      interface (returnType, inputVars, outputVars, inOutVars, localVars, tempVars, externalVars, globalVars,
                 accessVars; addData)
      actions/action name → body
      transitions/transition name → body
      body (ST | IL | LD | FBD | SFC; addData)
      addData (CODESYS: Method, Property, InterfaceAsPlainText, inheritance, attributes, …)
  instances/configurations/configuration name
    resource name → task name priority interval single → pouInstance name typeName; globalVars
    globalVars (configuration level)
  addData (CODESYS: project structure, global variable lists, libraries, task settings, …)
```
- **File selection**: `.xml` is read only when the first 4 KB contain `http://www.plcopen.org/xml/tc6_0201` or
  `tc6_0200` (or `tc6_0100`; none seen). Other XML is skipped without a diagnostic.
- **XML safety and errors**: shared `XmlInput` (no DTDs, no external entities, depth limit). Malformed XML → one
  diagnostic, everything before it is kept (27 corpus files).
- **Text** (ST bodies, expressions, declarations, documentation) sits in `<xhtml xmlns=…>` or `<xhtml:p>` elements, as
  CDATA (Beremiz, OpenPLC) or entity-escaped text (CODESYS). It is taken from the raw file with `XmlText`, so spans
  are exact.
- **Reading strategy**: StAX builds a small element tree per top-level unit (one `dataType`, one `pou`, one
  `configuration`, one global variable list) and hands it to the translator before reading on. Memory is bounded by
  the largest POU, not the 36 MB file. `position` children become coordinates on their parent; documentation and
  unknown addData are not stored.

## 2. Types (TC6 "elementary/derived types")
| Element | TypeRef |
|---|---|
| `<BOOL/>`, `<INT/>`, … `<TIME/>`, `<DT/>` (elementary, upper case) | `Named` |
| `<string length="n"/>`, `<wstring/>` | `StringType` |
| `<derived name="T"/>` | `Named` |
| `<array><dimension lower upper/>… <baseType>…</baseType></array>` | `ArrayType` |
| `<pointer><baseType>…</pointer>` (CODESYS POINTER TO); `<reference>` (verify) | `PointerType` |
| `<struct><variable …/></struct>` | `StructType` |
| `<enum><values><value name value?/></values><baseType?></enum>` | `EnumType` |
| `<subrangeSigned|subrangeUnsigned><range lower upper/><baseType></…>` | `Named` with range |

Initial values: `<simpleValue value="…"/>` (the value is ST text: `T#100ms`, `16#FF`, `'abc'`, `E.Auto`, parsed as
an ST expression), `<arrayValue><value repetitionValue? ><simpleValue/>…`, `<structValue><value member>…` →
`Initializer`.

## 3. POU interfaces (decision 1: plain text first)
- CODESYS writes the declaration twice: the structured `<interface>` and, in `interface/addData`,
  `InterfaceAsPlainText` (the POU header and VAR blocks as source, 346 files / 50 repos). When the plain text is
  present it is parsed with the CODESYS `declarationPart` rule, exactly like a TwinCAT `<Declaration>`: header, kind,
  EXTENDS/IMPLEMENTS, access modifiers, pragmas and comments come from it. Otherwise the structured interface is used:
  - section elements → `VarBlock` sections; `constant`, `retain`, `nonretain` attributes → qualifiers;
  - `returnType` → result type;
  - CODESYS `pouinheritance` addData (`<Inheritance><Extends>`/`<Implements>`) → EXTENDS/IMPLEMENTS;
  - `attributes` addData (`<Attribute Name Value/>`) → attribute pragmas.
- Members:
  - TC6 `actions/action` → ACTION, `transitions/transition` → TRANSITION;
  - CODESYS addData `Method` (with its own interface and body) → METHOD;
  - `Property` → PROPERTY with `GetAccessor`/`SetAccessor` → PROPERTY_GET/SET.
- `pouType` maps program → PROGRAM, functionBlock → FUNCTION_BLOCK, function → FUNCTION. 12 corpus POUs have none:
  FUNCTION_BLOCK, plus a diagnostic.

## 4. ST and IL bodies
- ST is parsed with the CODESYS grammar (`statementsOnly`); Beremiz/OpenPLC write plain IEC ST, which it accepts.
- IL (21 bodies): not analysed; info diagnostic; the POU counts as opaque for Q001.

## 5. LD and FBD networks (TC6 §6; IEC 61131-3 §8)
Graph elements, each with `localId`:

| Element | Pins | Meaning |
|---|---|---|
| `leftPowerRail` / `rightPowerRail` | out / in | power source / sink (LD) |
| `contact negated storage? edge=none|rising|falling` + `<variable>` | in, out | AND with the variable (NOT if negated; edge: one-shot of the variable) |
| `coil negated storage=none|set|reset edge?` + `<variable>` | in, out | assignment from the flow; passes the flow on |
| `block typeName instanceName? executionOrderId` with `inputVariables/inOutVariables/outputVariables/variable formalParameter negated?` | named | function (no instance) or FB call; `EN`/`ENO` pins in LD |
| `inVariable` / `outVariable` / `inOutVariable` + `<expression>` | out / in / both | read / write / both |
| `connector name` / `continuation name` | in / out | a wire drawn as a named jump between two points |
| `jump label` / `label label` / `return` | in / — / in | control flow |
| `comment`, `vendorElement` (CODESYS network titles, `ldbranchid`) | — | not semantic |

- A connection is stored at the **input**: `connectionPointIn/connection refLocalId formalParameter?`.
  `formalParameter` names the output pin of the source block. Several connections into one input are an OR in LD
  (parallel wires) and an error in FBD.
- **Networks**: the connected components of the graph. In LD they are between the rails; in CODESYS FBD they are also
  separated by `vendorElement` network titles. They are numbered top to bottom by their smallest `y`.
- **Order** (IEC §8.3.1): `executionOrderId` where the producer sets it (CODESYS FBD numbers its blocks; Beremiz and
  OpenPLC write 0). Otherwise a node runs after everything it reads; independent nodes run top to bottom, then left to
  right (`position y`, then `x`), which is what the Beremiz/OpenPLC compiler does (TODO(verify-format)).
- **Feedback** (IEC §8.3.1, "feedback paths"): a value read before it is computed in the same cycle is the value
  from the previous cycle. A variable read back (`inOutVariable`, an FB output) reads the variable; a function in a
  cycle has no previous value (diagnostic).
- **Lowering** to statements:
  - LD power flow:
    - contact → `flow AND x` (`NOT x` if negated; an edge contact is a call of the synthetic edge builtin
      `$R_TRIG(x)`/`$F_TRIG(x)`, with state, evaluated once);
    - parallel wires → OR;
    - coil → `x := flow` / `x := NOT flow` / `IF flow THEN x := TRUE|FALSE` (set/reset);
    - jump → `IF flow THEN JMP label`; return → `IF flow THEN RETURN`.
  - Blocks:
    - an FB instance → `inst(IN1 := …, …)` once, its outputs read as `inst.Q`;
    - a function → an expression, with the IEC operators as operators (`ADD` → `+`, n-ary chained);
    - EN connected to anything but TRUE → `IF en THEN …`; ENO = EN.
  - outVariable → `x := value`; inOutVariable → `x := value` and reads `x` afterwards.
  - A value used by more than one consumer, or with a side effect, is stored in a synthetic `$fbdN` first. Every block
    and edge is evaluated exactly once.
- Each network becomes `Statement.Network(number, network, lowered)`; the `Network` IR keeps every node with its pins,
  connections and coordinates for the viewer. Spans of lowered statements are their node's XML element.

## 6. SFC (TC6 §6.6; IEC 61131-3 §6.7; decision 3: IR + lowering)
| Element | |
|---|---|
| `step name initialStep` | a step; implicit flags `X` (active) and `T` (time active) |
| `transition` + `condition` (`inline` body: ST expression, or LD/FBD; or `reference name` to a transition member) | |
| `actionBlock` attached to a step: `action qualifier duration? indicator?` + `reference name` or `inline` body | |
| `selectionDivergence` / `selectionConvergence` | alternative branches (one transition fires) |
| `simultaneousDivergence` / `simultaneousConvergence` | parallel branches |
| `jumpStep targetName` | continues at a named step |
| `macroStep` | not seen in the corpus; diagnostic |

- Topology: like LD, connections are stored at inputs. The source steps of a transition are found by walking its
  input back through selection convergences (any one) and simultaneous convergences (all of them). Its target steps
  are found by walking forward through divergences and jump steps.
- **Lowering**, the classic SFC-to-ST translation:
  - every step `S` is declared as an implicit variable `S : STRUCT X : BOOL; T : TIME; END_STRUCT` (initial step:
    `X := TRUE`);
  - actions: `N`/none → `IF S.X THEN <action> END_IF`; `S`/`R` → set/reset a stored flag `$act_A`, and the action
    runs `IF $act_A THEN …`; `P`, `P1`, `P0` → `$R_TRIG(S.X)`/`$F_TRIG(S.X)`; time-qualified `L`, `D`, `SD`, `DS`, `SL`
    are kept on the IR and lowered like `N`/`S` (TODO(verify-format): timing semantics are not modelled);
  - transitions in their element order: `IF A.X [AND B.X …] AND cond THEN A.X := FALSE; … C.X := TRUE; … END_IF`;
  - a referenced action or transition is a call of that member; an inline body is lowered in place (inline ST
    conditions are parsed as expressions).
- The chart becomes `Statement.Chart(sfc, lowered)`; the `Sfc` IR keeps steps, transitions and action blocks for M7
  rules and the viewer.

- **Drawing (M10)**: `Sfc.drawing` keeps the chart as drawn — `step`, `macroStep`, `transition`, the four branch
  elements, `jumpStep` and `actionBlock` with `position`, `width`/`height` and the `connection` routes — for
  `SfcSvg`. A transition's text is its referenced name, its inline ST, or the expression of a variable wired to its
  graphical condition.

## 7. Configurations and tasks
`configuration` → `Configuration`, `resource` → `Resource`, `task name priority interval` → `Task` (`INTERVAL`,
`PRIORITY`), `pouInstance name typeName` → `ProgramInstance`, resource/configuration `globalVars` → `GlobalVars`.
CODESYS also puts the task interval in `tasksettings` addData (`<TaskSettings KindOfTask Interval>`), used when the
standard attribute is missing.

## 8. Finding places
`POU:Main/Network:3` for LD/FBD networks, `POU:Main/Chart` for SFC, `POU:Main/Line:n` for ST (the line within the
body text). Members add `/Method:M`, `/Action:A`.

## 9. Open points (TODO(verify-format))
1. Execution order when `executionOrderId` is 0 or missing (Beremiz/OpenPLC): assumed topological, then y, then x.
2. `<reference>` type element (CODESYS REFERENCE TO): element name and shape.
3. Timing qualifiers in SFC (L, D, SD, DS, SL): not modelled beyond N/S.
4. CODESYS LD in one `<LD>` with several networks: network boundaries assumed to be connected components (network
   titles only as a hint).
5. Edge on coils (`edge="rising"` on a coil): lowered as `x := $R_TRIG(flow)`.

## 10. Corpus results (M6)
`./gradlew :plc-devtools:corpusCheck -Ponly=PlcOpen` (one project per file), 2,827 files from 438 repos:

| measure | overall | repo-weighted | repos |
|---|---|---|---|
| files without any error, `tc6_0200` / `tc6_0201` | 94.13% / 91.89% | 90.22% / 90.63% | 177 / 274 |
| LD/FBD networks and SFC charts (23,461) lowered without an invalid node | 99.54% | 99.13% | 218 |
| ST bodies (25,105) without syntax error | 99.04% | 95.39% | 244 |
| names resolved / library / unresolved | 82.99% / 15.28% / 1.73% | 95.59% resolved+library | |

No clean SLL fallback (582 of 356,760 texts fell back, all with real errors). Found and handled on the way: ISO
`xsd:duration` task intervals (`PT0.02S`, TC6 2.01), unquoted string initial values (Beremiz), feedback through
functions (kept in a synthetic `$fbdN`). Remaining failures are tool output, not TC6: a whole POU pasted into an ST
body, instance names with `-`, `:=` structure literals in `simpleValue`, `name=value;` expressions, broken XML in test
fixtures and LLM-generated examples.

