---
id: PLCA-Q012
title: Loop without evident exit
severity: error
category: quality
dialects: all
tags: [reliability, watchdog]
plcopen: -
cwe: CWE-835
---
## Description

A WHILE or REPEAT loop whose condition is constant (`WHILE TRUE`, `UNTIL FALSE`) or reads only variables that the loop
body never changes, and whose body has no `EXIT`, `RETURN` or `JMP` out of the loop.

## Rationale

A PLC program runs inside a cyclic task: a loop that never ends keeps the cycle from finishing, the watchdog trips and
the controller stops, with every output in its fault state. Waiting for an input inside a loop never works either —
inputs are only read between cycles. Change the condition inside the loop, add an iteration limit, or restructure the
wait as a step of a state machine that is checked once per cycle.

Not reported when the loop may end in ways the analysis cannot see: the condition calls a function, method or
property or assigns a variable, reads through a pointer, reference or VAR_IN_OUT, or reads a global, an instance member
or a function block instance while the body calls other code; or a variable it reads has its address taken anywhere in
the POU.

## Noncompliant

```st
FUNCTION_BLOCK FB_Wait
VAR_INPUT
    bReady : BOOL;
END_VAR
VAR
    nTries : INT;
END_VAR
WHILE NOT bReady DO
    nTries := nTries + 1;
END_WHILE
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Wait
VAR_INPUT
    bReady : BOOL;
END_VAR
VAR
    nTries : INT;
END_VAR
WHILE NOT bReady AND nTries < 10 DO
    nTries := nTries + 1;
END_WHILE
END_FUNCTION_BLOCK
```
