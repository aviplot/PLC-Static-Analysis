---
id: PLCA-Q004
title: Division by zero
severity: error
category: quality
dialects: all
tags: [reliability, runtime-error]
plcopen: -
cwe: CWE-369
---
## Description

The divisor of `/`, `MOD`, `DIV(…)` or `MOD(…)` is a literal or constant that evaluates to zero.

## Rationale

An integer division by zero raises an exception on most runtimes and stops the PLC; a REAL division yields infinity
or NaN that spreads through the calculation. A divisor that is always zero is a bug, often a constant that was
changed or not yet set.

## Noncompliant

```st
FUNCTION F_Average : INT
VAR_INPUT
    nSum : INT;
END_VAR
VAR CONSTANT
    cSamples : INT := 0;
END_VAR
F_Average := nSum / cSamples;
END_FUNCTION
```

## Compliant

```st
FUNCTION F_Average : INT
VAR_INPUT
    nSum : INT;
END_VAR
VAR CONSTANT
    cSamples : INT := 8;
END_VAR
F_Average := nSum / cSamples;
END_FUNCTION
```
