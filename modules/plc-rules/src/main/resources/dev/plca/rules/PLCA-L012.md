---
id: PLCA-L012
title: Comparison outside the operand's range
severity: warning
category: logic
dialects: all
tags: [correctness]
plcopen: -
cwe: CWE-570
---
## Description

A comparison whose result its operand's declared type fixes: `nByte > 255` on a BYTE, `nCount < 0` on a UINT,
a subrange compared with a value outside it, a `STRING(n)` compared with a literal longer than `n`.

## Rationale

The comparison is always FALSE (or always TRUE), so the guarded code never runs (or the check never fails).
Common after a type change (INT to USINT) or when a negative check is copied to an unsigned variable. Only variables
and their members are checked: the type of an arithmetic expression does not bound its value (CODESYS computes
`INT + INT` in 32 bits). Also applies to comparisons in ladder (`GRT`, `LES`, …).

## Noncompliant

```st
PROGRAM P_Test
VAR
    nCount : UINT;
    bAlarm : BOOL;
END_VAR
IF nCount < 0 THEN
    bAlarm := TRUE;
END_IF
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Test
VAR
    nCount : INT;
    bAlarm : BOOL;
END_VAR
IF nCount < 0 THEN
    bAlarm := TRUE;
END_IF
END_PROGRAM
```
