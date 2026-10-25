---
id: PLCA-L010
title: Redundant part of a condition
severity: info
category: logic
dialects: all
tags: [clarity, redundancy]
plcopen: -
---
## Description

A part of an AND that the other parts already imply (`a > 10 AND a > 5`), a part of an OR that the other parts
already cover (`a > 10 OR a > 5`), or the same part twice. In ladder: the same contact twice in series.

## Rationale

The redundant part adds nothing, and frequently one of the limits is a typo for something else. Applies to all
conditions, including those of ladder rungs and FBD networks.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel > 10 AND nLevel > 5 THEN
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
IF nLevel > 10 AND nLevel < 50 THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```
