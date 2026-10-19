---
id: PLCA-Q009
title: Unreachable code
severity: warning
category: quality
dialects: all
tags: [maintainability, dead-code]
plcopen: CP2
cwe: CWE-561
parameters:
  includeNamedConstants: bool = false | Also report conditions that are constant only through named constants (VAR CONSTANT); off by default, since those are usually configuration switches
---
## Description

Code that can never run: statements after `RETURN`, `EXIT`, `CONTINUE` or `JMP` in the same statement list, an IF,
ELSIF or WHILE whose condition is always FALSE, ELSIF/ELSE branches after a condition that is always TRUE, and
Rockwell rungs disabled by an `AFI` instruction.

## Rationale

Dead code misleads the reader, who must work out that it never runs, and often hides a mistake: a `RETURN` left over
from debugging, or logic switched off with `IF FALSE THEN` and forgotten. PLCopen Coding Guidelines rule CP2 *All code
shall be used in the application*.

Only conditions built from literals are reported by default. A condition on a named constant (`IF cSimulation THEN`)
is usually a deliberate configuration switch; set `includeNamedConstants` to report those too. A label after a jump
makes the following code reachable again. TwinCAT's `RETURN(expression)` is not treated as an unconditional exit. Conditions generated from ladder, FBD and SFC are not checked, but a rung
whose main series contains `AFI` is reported: it is switched off for good.

## Noncompliant

```st
FUNCTION F_Limit : INT
VAR_INPUT
    nValue : INT;
END_VAR
F_Limit := nValue;
RETURN;
IF nValue > 100 THEN
    F_Limit := 100;
END_IF
END_FUNCTION
```

```st
PROGRAM P_Test
VAR
    bPump : BOOL;
END_VAR
IF FALSE THEN
    bPump := TRUE;
END_IF
END_PROGRAM
```

## Compliant

```st
FUNCTION F_Limit : INT
VAR_INPUT
    nValue : INT;
END_VAR
F_Limit := nValue;
IF nValue > 100 THEN
    F_Limit := 100;
END_IF
END_FUNCTION
```
