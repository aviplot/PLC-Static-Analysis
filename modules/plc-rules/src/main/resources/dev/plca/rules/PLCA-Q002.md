---
id: PLCA-Q002
title: CASE without ELSE
severity: warning
category: quality
dialects: all
tags: [defensive-programming, reliability]
plcopen: -
cwe: CWE-478
parameters:
  allowFullEnumCoverage: bool = true | Do not report a CASE on an enumeration whose labels cover every value
---
## Description

A CASE statement has no ELSE branch, so an unexpected selector value silently does nothing.

## Rationale

State machines are usually written as CASE statements. If the state variable takes a value no branch handles (a new
state, a corrupted value, a bad HMI write), the machine stops in an undefined state without any alarm. An ELSE branch
makes that case explicit: raise an error or go to a safe state. PLCopen rule L17 asks the same for IF; the guidelines
have no CASE rule, so no PLCopen id is given.

A CASE on an enumeration that handles every value is not reported by default.

## Noncompliant

```st
PROGRAM P_Sequence
VAR
    nStep : INT;
END_VAR
CASE nStep OF
    0: nStep := 10;
    10: nStep := 0;
END_CASE
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Sequence
VAR
    nStep : INT;
    bFault : BOOL;
END_VAR
CASE nStep OF
    0: nStep := 10;
    10: nStep := 0;
ELSE
    bFault := TRUE;
END_CASE
END_PROGRAM
```
