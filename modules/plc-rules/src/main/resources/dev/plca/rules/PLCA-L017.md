---
id: PLCA-L017
title: ELSE after a branch that always leaves
severity: info
category: logic
dialects: all
tags: [style, nesting]
plcopen: -
enabled: false
---
## Description

Every branch before ELSE ends with RETURN, EXIT, CONTINUE or JMP: the ELSE is not needed, its statements can
follow END_IF.

## Rationale

Guard clauses read best flat: `IF NOT bEnable THEN RETURN; END_IF` followed by the main logic. Off by default:
style guides differ.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF bAlarm THEN
    RETURN;
ELSE
    bPump := nLevel < 10;
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
IF bAlarm THEN
    RETURN;
END_IF
bPump := nLevel < 10;
END_PROGRAM
```
