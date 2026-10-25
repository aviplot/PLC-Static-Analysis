---
id: PLCA-L021
title: Empty branch
severity: info
category: logic
dialects: all
tags: [style, clarity]
plcopen: -
enabled: false
---
## Description

An IF, ELSIF, ELSE or CASE branch without statements and without a comment saying why. An empty THEN with an
ELSE is PLCA-L018.

## Rationale

An empty branch is either unfinished code or a deliberate "do nothing"; a comment tells the reader which. Off
by default.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
CASE nLevel OF
    0: bPump := FALSE;
    10:
ELSE
    bAlarm := TRUE;
END_CASE
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
CASE nLevel OF
    0: bPump := FALSE;
    10: // waiting for the operator: nothing to do
ELSE
    bAlarm := TRUE;
END_CASE
END_PROGRAM
```
