---
id: PLCA-Q006
title: Write to a VAR_INPUT
severity: warning
category: quality
dialects: all
tags: [maintainability, interface]
plcopen: CP17
---
## Description

A VAR_INPUT of a function, function block or method is written inside that POU (or a method or action of it).

## Rationale

Inputs are set by the caller on every call. Overwriting one inside the POU hides the caller's value, and the effect
depends on whether the caller passes the input on the next call; for function blocks the changed value can also be
read back from outside. Copy the input to a local variable and change that. PLCopen Coding Guidelines rule CP17
*Usage of parameters shall match their declaration mode* ("each input parameter should not be written"). Writes
through a POINTER or REFERENCE input change the caller's data and are not reported.

## Noncompliant

```st
FUNCTION_BLOCK FB_Scale
VAR_INPUT
    rRaw : REAL;
END_VAR
VAR_OUTPUT
    rScaled : REAL;
END_VAR
IF rRaw < 0.0 THEN
    rRaw := 0.0;
END_IF
rScaled := rRaw * 0.1;
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Scale
VAR_INPUT
    rRaw : REAL;
END_VAR
VAR_OUTPUT
    rScaled : REAL;
END_VAR
VAR
    rClamped : REAL;
END_VAR
rClamped := MAX(rRaw, 0.0);
rScaled := rClamped * 0.1;
END_FUNCTION_BLOCK
```
