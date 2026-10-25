---
id: PLCA-L006
title: Duplicate or overlapping CASE label
severity: warning
category: logic
dialects: all
tags: [correctness, dead-code]
plcopen: -
cwe: CWE-561
---
## Description

A CASE label (or range) that repeats values of an earlier label in the same CASE: only the first branch is
taken for those values.

## Rationale

`CASE nStep OF 10: … 5..15: …` — step 10 never reaches the second branch. Usually a copied step number in a
state machine. Whether a compiler rejects an exact duplicate depends on the vendor and version; overlapping ranges
are accepted more often.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
CASE nLevel OF
    0: bPump := FALSE;
    10: bPump := TRUE;
    5..15: bAlarm := TRUE;
ELSE
    bAlarm := FALSE;
END_CASE
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
CASE nLevel OF
    0: bPump := FALSE;
    10: bPump := TRUE;
    11..15: bAlarm := TRUE;
ELSE
    bAlarm := FALSE;
END_CASE
END_PROGRAM
```
