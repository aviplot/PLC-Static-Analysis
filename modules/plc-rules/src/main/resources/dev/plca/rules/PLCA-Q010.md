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
(REPEAT at least once), and RETURN, EXIT and CONTINUE end a path. Passing the variable as VAR_IN_OUT or to a call whose
parameters are not known (library code), taking its address or writing a part of it counts as an assignment. An
accumulator that builds on the default (`nSum := nSum + x`, `F := SHL(F, 1)`) is deliberate in CODESYS-style code and is
reported only for Siemens, where classic blocks do not initialise temporaries. Static variables of function blocks and programs are not checked:
keeping a value from one cycle to the next is what they are for. Structures and arrays, bodies with JMP or `__TRY`,
and Rockwell code (which has no temporaries) are not checked.

## Noncompliant

```st
FUNCTION_BLOCK FB_Door
VAR_INPUT
    bOpenCmd : BOOL;
END_VAR
VAR_OUTPUT
    bMotor : BOOL;
END_VAR
VAR_TEMP
    bWasOpen : BOOL;
END_VAR
IF bOpenCmd AND NOT bWasOpen THEN
    bMotor := TRUE;
END_IF
bWasOpen := bOpenCmd;
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Door
VAR_INPUT
    bOpenCmd : BOOL;
END_VAR
VAR_OUTPUT
    bMotor : BOOL;
END_VAR
VAR
    bWasOpen : BOOL;
END_VAR
IF bOpenCmd AND NOT bWasOpen THEN
    bMotor := TRUE;
END_IF
bWasOpen := bOpenCmd;
END_FUNCTION_BLOCK
```
