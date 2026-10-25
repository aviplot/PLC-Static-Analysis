---
id: PLCA-L023
title: Temporary value overwritten before it is read
severity: info
category: logic
dialects: all
tags: [correctness, dead-store]
plcopen: -
cwe: CWE-563
---
## Description

A VAR_TEMP (or a VAR of a FUNCTION or METHOD) is assigned and then assigned again in the same statement list
before anything reads the first value.

## Rationale

The first assignment is wasted, and often the second one was meant for another variable. Static variables are
not checked: they keep their value for the next cycle, and another task or the HMI may read it in between. Not
reported when the first value comes from a call, when the variable's address is taken, or across loops, labels and
jumps.

## Noncompliant

```st
FUNCTION F_Scale : INT
VAR_INPUT
    nRaw : INT;
END_VAR
VAR
    nTmp : INT;
END_VAR
nTmp := nRaw * 2;
nTmp := nRaw * 3;
F_Scale := nTmp;
END_FUNCTION
```

## Compliant

```st
FUNCTION F_Scale : INT
VAR_INPUT
    nRaw : INT;
END_VAR
VAR
    nTmp : INT;
END_VAR
nTmp := nRaw * 2;
nTmp := nTmp + nRaw;
F_Scale := nTmp;
END_FUNCTION
```
