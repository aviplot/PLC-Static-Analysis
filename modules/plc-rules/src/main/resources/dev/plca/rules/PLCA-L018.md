---
id: PLCA-L018
title: Empty THEN with an ELSE
severity: info
category: logic
dialects: all
tags: [style]
plcopen: -
enabled: false
---
## Description

`IF c THEN ELSE … END_IF`: negate the condition and drop the ELSE. Not reported when THEN holds a comment saying
why.

## Rationale

The reader has to negate the condition in their head to see when the code runs. Off by default.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel < 10 THEN
ELSE
    bPump := TRUE;
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
IF nLevel >= 10 THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```
