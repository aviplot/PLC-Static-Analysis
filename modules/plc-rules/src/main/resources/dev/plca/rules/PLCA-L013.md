---
id: PLCA-L013
title: Identical branches
severity: warning
category: logic
dialects: all
tags: [correctness, duplication]
plcopen: -
---
## Description

Two branches with the same statements: every branch of an IF (ELSE included) doing the same, a branch that
repeats the one before it, or two CASE branches with the same statements.

## Rationale

When every branch does the same, the conditions have no effect — usually one branch was copied and not edited.
Two adjacent branches doing the same can be merged with OR; two CASE branches doing the same can share one label
list (`10, 20:`). Empty branches are not compared (PLCA-L021).

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel > 10 THEN
    bPump := TRUE;
ELSE
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
IF nLevel > 10 THEN
    bPump := TRUE;
ELSE
    bPump := FALSE;
END_IF
END_PROGRAM
```
