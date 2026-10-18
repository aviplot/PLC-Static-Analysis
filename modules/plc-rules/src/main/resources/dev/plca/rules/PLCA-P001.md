---
id: PLCA-P001
title: Double coil
severity: warning
category: plc
dialects: all
tags: [plc, outputs, determinism]
plcopen: CP12
parameters:
  includeLocals: bool = false | Also check VAR/VAR_STAT variables of function blocks and programs (within one body)
---
## Description

An output-like variable — a physical output (`AT %Q…`), a global variable, or the POU's own VAR_OUTPUT — is written
unconditionally in more than one place, so only the last write of each cycle takes effect.

## Rationale

This is the structured-text form of the ladder "double coil": two rungs driving the same coil. The earlier write is
dead, the behaviour depends on execution order, and the logic that decides the output is spread over the
application. Compute the value once, in one place. PLCopen Coding Guidelines rule CP12 *Physical outputs shall be
written once per PLC cycle* covers physical outputs; this rule also checks globals and FB outputs.

To stay quiet on correct code, only unconditional writes count (`:=` or `=>` in a top-level statement of a body),
`S=`/`R=` latches and writes inside IF/CASE/loops are ignored, and a second write in the same body is not reported
when the variable is read in between (sequential use of a working variable).

## Noncompliant

```st
PROGRAM P_Conveyor
VAR
    bStart : BOOL;
    bJog : BOOL;
    qMotor AT %QX0.0 : BOOL;
END_VAR
qMotor := bStart;
qMotor := bJog;
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Conveyor
VAR
    bStart : BOOL;
    bJog : BOOL;
    qMotor AT %QX0.0 : BOOL;
END_VAR
qMotor := bStart OR bJog;
END_PROGRAM
```
