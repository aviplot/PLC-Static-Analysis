---
id: PLCA-L007
title: Tautological condition
severity: warning
category: logic
dialects: all
tags: [correctness]
plcopen: -
cwe: CWE-571
---
## Description

A condition that is always true by itself: `a > 5 OR a <= 5`, `b OR NOT b`, `a = a`. In ladder: parallel
branches examining the same tag open and closed, `[XIC(A), XIO(A)]`.

## Rationale

The condition has no effect, so the logic it was meant to gate always runs. Often OR written for AND, or a
negation in the wrong place. `x = x` on a REAL is the NaN test and is not reported.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel > 5 OR nLevel <= 5 THEN
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
IF nLevel > 5 OR bAlarm THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```
