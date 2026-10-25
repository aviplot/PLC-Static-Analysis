---
id: PLCA-L001
title: Contradictory nested condition
severity: warning
category: logic
dialects: all
tags: [correctness, dead-code]
plcopen: -
cwe: CWE-561
---
## Description

An IF, ELSIF, WHILE condition or CASE label that can never be true where it stands, given the enclosing
conditions (and nothing changed their operands in between): its branch never runs.

## Rationale

The inner test contradicts what the outer test already established: `IF nLevel > 100 THEN IF nLevel < 50 THEN`.
Either the inner condition is wrong (a typo in the limit, the wrong variable) or the code is dead. Both need a look.

The analysis follows assignments, calls and loops: a write to an operand between the two tests, a call that may
change it (a global, a VAR_IN_OUT, an FB output), a pointer or reference to it, or a jump label in between drops what
was known. Conditions fixed by constants (`IF cSimulation THEN`) are configuration switches and are not folded.

When the proof relies on a global that a task other than this POU's writes (see PLCA-P004), the finding is reported at
`info`: the other task may change the value between the two tests, so the branch may run after all — itself a
sign of a race.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel > 100 THEN
    IF nLevel < 50 THEN
        bAlarm := TRUE;
    END_IF
END_IF
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel > 100 THEN
    nLevel := 0;
    IF nLevel < 50 THEN
        bAlarm := TRUE;
    END_IF
END_IF
END_PROGRAM
```
