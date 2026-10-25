---
id: PLCA-L016
title: Nested IF to merge with AND
severity: info
category: logic
dialects: all
tags: [style, nesting]
plcopen: -
enabled: false
---
## Description

An IF without ELSE whose only statement is another IF without ELSE: one IF with AND says the same.

## Rationale

One level less of nesting. Not reported when the inner condition depends on the outer one to be safe — it
dereferences a pointer, indexes an array, divides or calls something — because ST's AND evaluates both sides. Off by
default: style guides differ.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF bPump THEN
    IF nLevel > 10 THEN
        bAlarm := TRUE;
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
IF bPump AND nLevel > 10 THEN
    bAlarm := TRUE;
END_IF
END_PROGRAM
```
