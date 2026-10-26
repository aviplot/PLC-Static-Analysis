---
id: PLCA-L015
title: ELSE that holds only an IF
severity: info
category: logic
dialects: all
tags: [style, nesting]
plcopen: -
---
## Description

An ELSE whose only statement is another IF: write ELSIF.

## Rationale

ELSIF says the same with one level of nesting less, and keeps the chain of cases in one place. Off by default
(select `PLCA-L015` or `all`): style guides differ.

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
    IF bAlarm THEN
        bPump := FALSE;
    END_IF
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
ELSIF bAlarm THEN
    bPump := FALSE;
END_IF
END_PROGRAM
```
