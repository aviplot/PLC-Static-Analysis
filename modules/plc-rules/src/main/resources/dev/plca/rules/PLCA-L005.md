---
id: PLCA-L005
title: Duplicate condition in an IF chain
severity: warning
category: logic
dialects: all
tags: [correctness, dead-code]
plcopen: -
cwe: CWE-561
---
## Description

The same condition appears twice in one IF / ELSIF chain (also when written differently, such as `a > 5` and
`5 < a`): the second branch never runs.

## Rationale

A copied branch whose condition was not edited: the logic meant for the second case is never executed.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel > 5 THEN
    bPump := TRUE;
ELSIF 5 < nLevel THEN
    bAlarm := TRUE;
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
IF nLevel > 50 THEN
    bAlarm := TRUE;
ELSIF nLevel > 5 THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```
