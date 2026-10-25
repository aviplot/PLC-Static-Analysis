---
id: PLCA-L019
title: BOOL set through IF
severity: info
category: logic
dialects: all
tags: [style]
plcopen: -
enabled: false
---
## Description

`IF c THEN b := TRUE; ELSE b := FALSE; END_IF` is `b := c;` (or `b := NOT c;` the other way round).

## Rationale

One line instead of five, and it makes plain that the BOOL follows the condition in every cycle. Off by
default.

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
bPump := nLevel > 10;
END_PROGRAM
```
