---
id: PLCA-P006
title: Jump instruction
severity: info
category: plc
dialects: all
tags: [maintainability, control-flow]
plcopen: CP19
---
## Description

A jump: ST `JMP`, Siemens SCL `GOTO`, a jump in a PLCopen LD/FBD network, or a Rockwell ladder `JMP`.

## Rationale

Jumps make the order of execution hard to follow. In ladder they are worse: the rungs a `JMP` skips are not scanned,
so their outputs keep their last state, which is easy to miss when reading the logic. PLCopen Coding Guidelines rule
CP19 *Usage of Jump and Return should be avoided*. Use IF, CASE or loops in text languages and explicit conditions in
ladder. One finding per jump; the label is not reported separately.

## Noncompliant

```st
PROGRAM P_Jump
VAR
    nStep : INT;
END_VAR
IF nStep = 0 THEN
    JMP lEnd;
END_IF
nStep := nStep + 1;
lEnd:
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Jump
VAR
    nStep : INT;
END_VAR
IF nStep <> 0 THEN
    nStep := nStep + 1;
END_IF
END_PROGRAM
```
