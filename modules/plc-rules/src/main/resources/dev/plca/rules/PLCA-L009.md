---
id: PLCA-L009
title: ELSIF that should be ELSE
severity: info
category: logic
dialects: all
tags: [clarity, redundancy]
plcopen: -
---
## Description

When the earlier conditions of the chain are false, this ELSIF condition is always true
(`IF a > b THEN … ELSIF a <= b THEN`). Write ELSE; any branch after it never runs.

## Rationale

An ELSIF suggests a remaining case where neither branch runs; there is none. If there was meant to be one, the
condition is wrong.

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
ELSIF nLevel <= 10 THEN
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
IF nLevel > 10 THEN
    bPump := TRUE;
ELSE
    bPump := FALSE;
END_IF
END_PROGRAM
```
