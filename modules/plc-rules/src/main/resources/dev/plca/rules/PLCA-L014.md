---
id: PLCA-L014
title: Self-assignment
severity: warning
category: logic
dialects: all
tags: [correctness]
plcopen: -
---
## Description

`x := x;` changes nothing. In ladder: `MOV(A, A)`, or an output instruction on the tag its only contact
examines (`XIC(A) OTE(A)`).

## Rationale

Almost always another variable was meant (`fbMotor.bRun := bRun;` written as `bRun := bRun;`). Assignments with a
call on either side are not reported.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
bPump := bPump;
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
bPump := bAlarm;
END_PROGRAM
```
