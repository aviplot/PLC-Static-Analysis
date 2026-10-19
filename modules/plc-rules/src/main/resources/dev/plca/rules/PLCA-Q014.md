---
id: PLCA-Q014
title: Magic number
severity: info
category: quality
dialects: all
tags: [maintainability, style]
enabled: false
parameters:
  allowed: list = 2, 10, 100, 1000 | Numbers that are not reported besides 0, 1 and -1
---
## Description

A decimal number in logic other than 0, 1, -1 and the `allowed` values. Off by default; select `PLCA-Q014` (or `all`)
to run it.

## Rationale

A bare number says nothing about what it is: is 4500 a speed, a limit or an error code? Naming it as a constant or a
parameter documents it, and changing it later means changing one place. Not reported: CASE labels, array indexes, FOR
bounds, initial values, typed and based literals (`T#5S`, `16#FF`, `INT#3`), and code generated from ladder, FBD and
SFC.

## Noncompliant

```st
FUNCTION_BLOCK FB_Tank
VAR_INPUT
    rLevel : REAL;
END_VAR
VAR_OUTPUT
    bHigh : BOOL;
END_VAR
bHigh := rLevel > 87.5;
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Tank
VAR_INPUT
    rLevel : REAL;
END_VAR
VAR_OUTPUT
    bHigh : BOOL;
END_VAR
VAR CONSTANT
    cHighLevel : REAL := 87.5;
END_VAR
bHigh := rLevel > cHighLevel;
END_FUNCTION_BLOCK
```
