---
id: PLCA-Q013
title: Complex POU body
severity: info
category: quality
dialects: all
tags: [maintainability, metrics]
plcopen: CP9
parameters:
  maxNesting: int = 5 | Deepest allowed nesting of IF, CASE, FOR, WHILE and REPEAT
  maxComplexity: int = 20 | Highest allowed cyclomatic complexity of one POU body
---
## Description

A POU body nests IF, CASE, FOR, WHILE or REPEAT statements deeper than `maxNesting`, or its cyclomatic complexity is
above `maxComplexity`. Complexity is 1 plus one for every IF/ELSIF condition, CASE branch, FOR, WHILE and REPEAT.

## Rationale

Deeply nested and highly branched code is hard to read, review and test, and is where commissioning changes go wrong.
PLCopen Coding Guidelines rule CP9 *Limit the complexity of POU code*. Split the logic into methods, actions or
functions, or replace nested IFs with a CASE state machine. The limits are parameters; CODESYS and TwinCAT ship their
own metric checks off by default, and so this rule reports at `info`.

Bodies generated from ladder, FBD and SFC are not measured: a ladder routine is a list of rungs, and each rung's
lowered conditions are not nesting the engineer wrote.

## Noncompliant

```st
FUNCTION F_Deep : INT
VAR_INPUT
    a, b, c, d, e, f : BOOL;
END_VAR
IF a THEN
    IF b THEN
        IF c THEN
            IF d THEN
                IF e THEN
                    IF f THEN
                        F_Deep := 1;
                    END_IF
                END_IF
            END_IF
        END_IF
    END_IF
END_IF
END_FUNCTION
```

## Compliant

```st
FUNCTION F_Flat : INT
VAR_INPUT
    a, b, c, d, e, f : BOOL;
END_VAR
IF a AND b AND c AND d AND e AND f THEN
    F_Flat := 1;
END_IF
END_FUNCTION
```
