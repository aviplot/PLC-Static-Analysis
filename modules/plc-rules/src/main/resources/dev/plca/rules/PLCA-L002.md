---
id: PLCA-L002
title: Unreachable ELSIF
severity: warning
category: logic
dialects: all
tags: [correctness, dead-code]
plcopen: -
cwe: CWE-561
---
## Description

An ELSIF condition that is false whenever the conditions before it are false: the branch never runs.

## Rationale

In `IF nLevel > 10 THEN … ELSIF nLevel > 20 THEN` every level above 20 is already above 10, so the second branch
is dead. Usually the order is wrong (test the larger limit first) or a limit is mistyped. A branch with the very
same condition as an earlier one is PLCA-L005.

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
ELSIF nLevel > 20 THEN
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
IF nLevel > 20 THEN
    bAlarm := TRUE;
ELSIF nLevel > 10 THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```
