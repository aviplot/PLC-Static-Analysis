---
id: PLCA-L004
title: Self-contradictory condition
severity: warning
category: logic
dialects: all
tags: [correctness]
plcopen: -
cwe: CWE-570
---
## Description

A condition that can never be true by itself: `a > 10 AND a < 5`, `b AND NOT b`, `a <> a`. In ladder: the same
tag examined open and closed in series, `XIC(A) XIO(A)`.

## Rationale

The code it guards never runs, or the value it computes is always FALSE. Typical causes are AND written for OR
(`nMode = 1 AND nMode = 2`) and a swapped limit. Applies to conditions anywhere — IF, WHILE, assignments, call
arguments — and to the conditions of ladder rungs and FBD networks. Constant conditions (`IF FALSE`) are PLCA-Q009,
comparisons the operand's type can never satisfy are PLCA-L012.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel = 1 AND nLevel = 2 THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel = 1 OR nLevel = 2 THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```
