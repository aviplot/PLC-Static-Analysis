---
id: PLCA-L024
title: NOT before a comparison
severity: info
category: logic
dialects: all
tags: [style]
plcopen: -
enabled: false
---
## Description

`NOT (a = b)` reads better as `a <> b`, `NOT (a < b)` as `a >= b`.

## Rationale

The comparison operator says it directly. Off by default: frequent in older code. On REAL operands the two forms
differ only for NaN.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
bPump := NOT (nLevel = 10);
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
bPump := nLevel <> 10;
END_PROGRAM
```
