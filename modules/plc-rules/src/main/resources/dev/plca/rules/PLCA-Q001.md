---
id: PLCA-Q001
title: Unused variable
severity: warning
category: quality
dialects: all
tags: [maintainability, unused-code]
plcopen: CP24
cwe: CWE-563
parameters:
  sparePattern: regex = (?i)^[a-z]{0,4}_?(spare|reserve|dummy|unused) | Names of deliberate spare variables (CP24 exception); never reported
  includeGlobals: bool = false | Also report global variables no POU uses (off by default: HMIs and ADS clients often read globals)
---
## Description

A local variable (VAR, VAR_TEMP, VAR_STAT, VAR_INST) is declared but never read or written by any code, initial
value or declaration in the project.

## Rationale

Unused declarations make a POU harder to read and suggest unfinished or dead logic; they also use memory. PLCopen
Coding Guidelines rule CP24 *Do not declare variables that are not used*.

Not reported, because something outside the parsed code may use the variable: variables with an attribute pragma
(HMI, OPC UA or ADS exposure), variables with an `AT` address (I/O mapping), names matching `sparePattern`, and every
variable of a POU with a graphical or unparsable body. Inputs and outputs are interface, not locals, and are not
checked here.

## Noncompliant

```st
FUNCTION_BLOCK FB_Pump
VAR_INPUT
    bStart : BOOL;
END_VAR
VAR
    bRunning : BOOL;
    nOldCounter : INT;
END_VAR
bRunning := bStart;
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Pump
VAR_INPUT
    bStart : BOOL;
END_VAR
VAR
    bRunning : BOOL;
    nSpare1 : INT;
END_VAR
bRunning := bStart;
END_FUNCTION_BLOCK
```
