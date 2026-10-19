---
id: PLCA-Q003
title: Equality comparison of floating-point values
severity: warning
category: quality
dialects: all
dialectSeverity: rockwell=info
tags: [reliability, numerics]
plcopen: CP8
cwe: CWE-1077
parameters:
  allowZero: bool = true | Do not report comparisons with 0 / 0.0 (the exception CP8 itself allows)
---
## Description

A REAL or LREAL value is compared with `=` or `<>`.

## Rationale

Floating-point values carry rounding errors, so a computed value is rarely exactly equal to another; the comparison
may never become TRUE (a sequence that never advances) or flicker. Compare the difference against a tolerance
instead. PLCopen Coding Guidelines rule CP8 *Floating point comparison shall not be equality or inequality*.

Comparisons with zero are not reported by default (CP8 lists them as its exception: a value set to 0.0 compares
exactly), nor is `x = x`, the usual test for NaN.

A value compared with its own copy (`IF a <> last THEN last := a;`, ladder `NEQ(a,last) MOV(a,last)`) is change
detection, exact by construction, and not reported. Rockwell (L5X): comparing two tags that no code of the program
or AOI writes (`EQU(Cfg_InpMax,Cfg_InpMin)` to flag an empty range) compares settings and is not reported either.

Ladder (Rockwell) findings are `info` by default: in ladder, exact REAL comparisons are mostly sentinel and
configuration checks. Raise them with `dialects: {rockwell: {severity: {PLCA-Q003: warning}}}` in `plca.yaml`.

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
