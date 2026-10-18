---
id: PLCA-Q003
title: Equality comparison of floating-point values
severity: warning
category: quality
dialects: all
tags: [reliability, numerics]
plcopen: CP8
cwe: CWE-1077
---
## Description

A REAL or LREAL value is compared with `=` or `<>`.

## Rationale

Floating-point values carry rounding errors, so a computed value is rarely exactly equal to another; the comparison
may never become TRUE (a sequence that never advances) or flicker. Compare the difference against a tolerance
instead. PLCopen Coding Guidelines rule CP8 *Floating point comparison shall not be equality or inequality*.

## Noncompliant

```st
FUNCTION F_AtTarget : BOOL
VAR_INPUT
    rPosition : REAL;
    rTarget : REAL;
END_VAR
F_AtTarget := rPosition = rTarget;
END_FUNCTION
```

## Compliant

```st
FUNCTION F_AtTarget : BOOL
VAR_INPUT
    rPosition : REAL;
    rTarget : REAL;
END_VAR
F_AtTarget := ABS(rPosition - rTarget) < 0.001;
END_FUNCTION
```
