---
id: PLCA-S002
title: Externally writable value used without a check
severity: warning
category: security
dialects: all
tags: [security, input-validation]
cwe: CWE-20
parameters:
  externalPattern: regex = (?i)(^|[._])(hmi|scada|visu|opc|remote|ext)[._a-z0-9]* | Names (or list.name) of variables that an HMI, SCADA system or other client writes, in dialects whose exports do not say so
---
## Description

A value that can be written from outside the PLC — by an HMI, a SCADA system or another network client — drives a
physical output, or is used as an array index, with no range or validity check in between.

## Rationale

Anything an operator panel or network client can write must be treated as untrusted: a typing error, a misconfigured
HMI or an attacker can send any value. A setpoint passed straight to an analog output can drive the process outside
its limits; a command bit wired straight to an output bypasses every interlock; an index that is out of range faults
the controller (Rockwell major fault, CODESYS exception) or overwrites neighbouring data. *Top 20 Secure PLC Coding
Practices* (ISA GCA / admeritia, 2021), practice 8 "Validate HMI input variables at the PLC, not just at the HMI" and
practice 9 "Validate indirections".

Externally writable means: Rockwell controller and program tags with `ExternalAccess="Read/Write"` (the default) that no
logic of the project writes; Siemens data block members that are not `ExternalWritable := 'False'` and that no block
writes; in CODESYS, TwinCAT and PLCopen, where any symbol may be reachable, variables with an OPC UA attribute or whose
name or list name matches `externalPattern` (HMI, SCADA, VISU, OPC, remote).

A use counts as checked when the value is compared, limited (`LIMIT`, `MIN`, `MAX`, `SEL`, `MUX`, ladder `LIM`), used
as a CASE selector or overwritten earlier in the body or in a condition around the use. Only pass-through is reported:
an output assigned from the value and constants alone, or set under a condition on the value alone. An interlock in
the expression is a check and silences the finding.

## Noncompliant

```st
VAR_GLOBAL
    GVL_HMI_rSpeedSetpoint : REAL;
    GVL_HMI_nRecipe : INT;
END_VAR

PROGRAM P_Drive
VAR
    qrSpeedOut AT %QD4 : REAL;
    arRecipes : ARRAY[0..9] OF REAL;
    rRecipe : REAL;
END_VAR
qrSpeedOut := GVL_HMI_rSpeedSetpoint;
rRecipe := arRecipes[GVL_HMI_nRecipe];
END_PROGRAM
```

## Compliant

```st
VAR_GLOBAL
    GVL_HMI_rSpeedSetpoint : REAL;
    GVL_HMI_nRecipe : INT;
END_VAR

PROGRAM P_Drive
VAR
    qrSpeedOut AT %QD4 : REAL;
    arRecipes : ARRAY[0..9] OF REAL;
    rRecipe : REAL;
END_VAR
qrSpeedOut := LIMIT(0.0, GVL_HMI_rSpeedSetpoint, 1500.0);
IF GVL_HMI_nRecipe >= 0 AND GVL_HMI_nRecipe <= 9 THEN
    rRecipe := arRecipes[GVL_HMI_nRecipe];
END_IF
END_PROGRAM
```
