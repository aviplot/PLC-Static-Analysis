---
id: PLCA-L011
title: IF chain that misses a boundary case
severity: warning
category: logic
dialects: all
tags: [correctness]
plcopen: -
---
## Description

An IF / ELSIF chain without ELSE over one value (or the difference of two) that leaves out only one to three
values, typically the boundary: `IF a < 0 THEN … ELSIF a > 0 THEN` misses `a = 0`; `IF a > b … ELSIF a < b` misses
`a = b`.

## Rationale

At exactly the boundary neither branch runs, and the output keeps its last value: a classic source of an
intermittent fault that shows only when a sensor reads exactly the limit. Write `>=` or `<=`, or add ELSE. Values the
enclosing conditions already exclude are not reported.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel < 100 THEN
    bPump := TRUE;
ELSIF nLevel > 100 THEN
    bPump := FALSE;
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
IF nLevel < 100 THEN
    bPump := TRUE;
ELSIF nLevel >= 100 THEN
    bPump := FALSE;
END_IF
END_PROGRAM
```
