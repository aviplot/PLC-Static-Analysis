---
id: PLCA-Q015
title: Naming convention
severity: info
category: quality
dialects: [codesys, twincat, siemens, plcopen]
tags: [maintainability, style, naming]
plcopen: N2
enabled: false
parameters:
  scopePrefix: regex = (?:g_?|c_?|m_?|_)? | Optional scope prefix before the type prefix (globals, constants, members)
  boolPrefix: regex = b|x | Prefix of BOOL and BIT variables
  integerPrefix: regex = n|i|u?[sdl]?i|by|w|dw|lw|di|udi|li|uli|si|usi|ui | Prefix of integer and bit-string variables
  realPrefix: regex = r|lr|f | Prefix of REAL and LREAL variables
  stringPrefix: regex = s|ws|str|c | Prefix of STRING, WSTRING and CHAR variables
  timePrefix: regex = t|tim|ltim|d|dt|tod|dat | Prefix of time and date variables
  arrayPrefix: regex = a|ar|arr | Prefix of array variables
  pointerPrefix: regex = p|ptr | Prefix of POINTER TO variables
  referencePrefix: regex = ref|r | Prefix of REFERENCE TO variables
  structVariablePrefix: regex = st|s|udt | Prefix of structure variables
  enumVariablePrefix: regex = e | Prefix of enumeration variables
  instancePrefix: regex = fb|inst|o|i | Prefix of function block instances
  interfaceVariablePrefix: regex = ip|itf|i | Prefix of interface variables
  functionBlockPrefix: regex = FB_|fb_ | Prefix of function block names
  functionPrefix: regex = F_|FC_|FUN_|f_ | Prefix of function names
  interfacePrefix: regex = I_|ITF_ | Prefix of interface names
  structPrefix: regex = ST_|T_|UDT_|typ | Prefix of structure type names
  enumPrefix: regex = E_|T_|ENUM_ | Prefix of enumeration type names
  unionPrefix: regex = U_|T_ | Prefix of union type names
  aliasPrefix: regex = T_|A_ | Prefix of alias and subrange type names
---
## Description

A variable whose name does not start with the prefix for its type (`bStart` for a BOOL, `fbTimer` for an instance), or
a function block, function, interface or user type whose name does not start with its kind's prefix (`FB_`, `F_`,
`I_`, `ST_`, `E_`). Off by default; select `PLCA-Q015` to run it, and set the prefixes to your team's convention.

## Rationale

Type prefixes let a reader see what a name holds without looking up its declaration, and a consistent style makes
code from different engineers read alike. PLCopen Coding Guidelines rules N2 *Define type prefixes for variables* and
N10 *Define prefixes for user defined types*. The defaults follow the widespread CODESYS/Beckhoff style; every prefix is
a regular expression parameter. A scope prefix (`scopePrefix`, e.g. `g_` for globals) may come first, and the type
prefix must be followed by an upper-case letter, a digit or `_`.

## Noncompliant

```st
FUNCTION_BLOCK Motor
VAR_INPUT
    Start : BOOL;
    speed : REAL;
END_VAR
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Motor
VAR_INPUT
    bStart : BOOL;
    rSpeed : REAL;
END_VAR
END_FUNCTION_BLOCK
```
