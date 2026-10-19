---
id: PLCA-P005
title: Pointer dereference without a check
severity: warning
category: plc
dialects: all
tags: [reliability, pointers]
plcopen: -
cwe: CWE-476
---
## Description

A pointer is dereferenced (`p^`) in a body that never compares it with 0, tests it with `__ISVALIDREF` or assigns it
from `ADR(…)` before the dereference.

## Rationale

Dereferencing a null pointer raises an access violation and stops the PLC (CODESYS, TwinCAT); a stale or zero pointer
passed in by a caller is a classic cause of a controller crash after an online change. Check the pointer before using
it, or use a REFERENCE with `__ISVALIDREF`. The check is approximate: any comparison of the pointer with 0 earlier in
the body, or in a condition around the dereference, counts. One finding per pointer and body.

## Noncompliant

```st
FUNCTION F_Read : INT
VAR_INPUT
    pValue : POINTER TO INT;
END_VAR
F_Read := pValue^;
END_FUNCTION
```

## Compliant

```st
FUNCTION F_Read : INT
VAR_INPUT
    pValue : POINTER TO INT;
END_VAR
IF pValue <> 0 THEN
    F_Read := pValue^;
END_IF
END_FUNCTION
```
