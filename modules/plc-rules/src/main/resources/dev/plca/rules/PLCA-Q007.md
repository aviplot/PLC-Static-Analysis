---
id: PLCA-Q007
title: Recursion
severity: error
category: quality
dialects: all
tags: [reliability, stack]
plcopen: CP13
cwe: CWE-674
---
## Description

A POU calls itself, directly or through a chain of other POUs (functions, methods, actions, FB bodies, property
accessors).

## Rationale

Each recursive call uses stack space, and PLC stacks are small and fixed; deep recursion can stop the controller.
Support for recursion also differs between vendors, so the code is less portable. Replace the recursion with a loop.
PLCopen Coding Guidelines rule CP13 *POUs shall not call themselves directly or indirectly*.

Cycles through a name that is declared more than once (overloaded methods in SIMATIC AX, duplicate copies of a POU)
are not reported, because which declaration a call refers to is not known.

## Noncompliant

```st
FUNCTION F_Factorial : DINT
VAR_INPUT
    n : DINT;
END_VAR
IF n <= 1 THEN
    F_Factorial := 1;
ELSE
    F_Factorial := n * F_Factorial(n - 1);
END_IF
END_FUNCTION
```

## Compliant

```st
FUNCTION F_Factorial : DINT
VAR_INPUT
    n : DINT;
END_VAR
VAR
    i : DINT;
END_VAR
F_Factorial := 1;
FOR i := 2 TO n DO
    F_Factorial := F_Factorial * i;
END_FOR
END_FUNCTION
```
