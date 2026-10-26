---
id: PLCA-L020
title: Same statement in every branch
severity: info
category: logic
dialects: all
tags: [style, duplication]
plcopen: -
---
## Description

Every branch of an IF with ELSE (or a CASE with ELSE) ends with the same statement, or starts with it: write it
once after (or before) the block.

## Rationale

Duplicated statements drift apart when one copy is edited. "Starts with" is reported only when the statement
writes nothing the conditions read, so moving it before the block keeps the meaning. Off by default.

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
    bAlarm := FALSE;
ELSE
    bPump := FALSE;
    bAlarm := FALSE;
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
bAlarm := FALSE;
END_PROGRAM
```
