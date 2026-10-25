---
id: PLCA-L003
title: Unreachable ELSE
severity: warning
category: logic
dialects: all
tags: [correctness, dead-code]
plcopen: -
cwe: CWE-561
---
## Description

The conditions of an IF chain cover every possible value, so its ELSE never runs.

## Rationale

`IF nLevel < 10 THEN … ELSIF nLevel >= 10 THEN … ELSE …` leaves nothing for ELSE. The ELSE is dead, and often the
case it was meant for (an invalid value, a sensor fault) is not handled at all.

A CASE ELSE is never reported, even when the labels cover every value of an enumeration: PLCA-Q002 asks for it as
a defensive branch against invalid values.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel < 10 THEN
    bPump := TRUE;
ELSIF nLevel >= 10 THEN
    bPump := FALSE;
ELSE
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
IF nLevel < 10 THEN
    bPump := TRUE;
ELSIF nLevel <= 100 THEN
    bPump := FALSE;
ELSE
    bAlarm := TRUE;
END_IF
END_PROGRAM
```
