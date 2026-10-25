---
id: PLCA-L008
title: Condition always true where it is
severity: warning
category: logic
dialects: all
tags: [correctness, redundancy]
plcopen: -
cwe: CWE-571
---
## Description

The enclosing conditions already imply this condition (`IF a > 10 THEN IF a > 5 THEN`): the test is redundant,
and any ELSIF or ELSE after it never runs.

## Rationale

A redundant test misleads the reader into thinking there is a case where it fails, and often the limit was meant
to be different. Same follow-up of writes and calls as PLCA-L001; proofs that rely on a global another task writes are
reported at `info`.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF nLevel > 10 THEN
    IF nLevel > 5 THEN
        bPump := TRUE;
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
IF nLevel > 10 THEN
    IF nLevel > 50 THEN
        bPump := TRUE;
    END_IF
END_IF
END_PROGRAM
```
