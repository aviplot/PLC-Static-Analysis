---
id: PLCA-Q010
title: Temporary variable read before assignment
severity: warning
category: quality
dialects: all
tags: [reliability, initialisation]
plcopen: CP3
cwe: CWE-457
---
## Description

A temporary variable without an initial value is read on some path before anything assigns it. Temporaries are the
locals of functions, methods and property accessors, VAR_TEMP of any POU, and a function's result.

## Rationale

IEC 61131-3 sets every variable to its type's default before it is used, so nothing is ever undefined — but a
temporary starts from that default on every call. Code that reads it first usually expects the value of the last call
(which only a function block's static variable keeps) or forgot an assignment on one branch. On Siemens S7 classic
(non-optimised) blocks, temporaries are not initialised at all and hold whatever the stack held. PLCopen Coding
Guidelines rule CP3 *All variables shall be initialized before being used*.

The analysis follows the structure of the body: IF and CASE branches are merged, a loop body may run zero times
(REPEAT at least once), and RETURN, EXIT and CONTINUE end a path. Passing the variable as VAR_IN_OUT, taking its address
or writing a part of it counts as an assignment. Static variables of function blocks and programs are not checked:
keeping a value from one cycle to the next is what they are for. Structures and arrays, bodies with JMP or `__TRY`,
and Rockwell code (which has no temporaries) are not checked.

## Noncompliant

```st
FUNCTION F_Average : REAL
VAR_INPUT
    arValues : ARRAY[0..9] OF REAL;
END_VAR
VAR
    i : INT;
    rSum : REAL;
END_VAR
FOR i := 0 TO 9 DO
    rSum := rSum + arValues[i];
END_FOR
F_Average := rSum / 10.0;
END_FUNCTION
```

## Compliant

```st
FUNCTION F_Average : REAL
VAR_INPUT
    arValues : ARRAY[0..9] OF REAL;
END_VAR
VAR
    i : INT;
    rSum : REAL;
END_VAR
rSum := 0.0;
FOR i := 0 TO 9 DO
    rSum := rSum + arValues[i];
END_FOR
F_Average := rSum / 10.0;
END_FUNCTION
```
