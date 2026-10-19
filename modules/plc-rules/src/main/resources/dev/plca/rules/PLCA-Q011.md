---
id: PLCA-Q011
title: Implicit narrowing conversion
severity: warning
category: quality
dialects: [codesys, twincat, siemens, plcopen]
tags: [reliability, numerics, types]
plcopen: CP25
cwe: CWE-197
---
## Description

A value is converted implicitly to a type that cannot hold every value of its own type: a wider integer or bit string
to a narrower one (`DINT` to `INT`, `DWORD` to `WORD`), a real to an integer, or `LREAL` to `REAL` — in an assignment,
an input argument or an output argument. Also an integer constant that does not fit the target type.

## Rationale

Implicit narrowing silently truncates: a counter stored in an INT wraps at 32767, a REAL assigned to an INT loses its
fraction (and overflows above 32767), an LREAL position loses precision. Making the conversion explicit with a
conversion function shows the reader that the range was considered, and is where a LIMIT belongs. PLCopen Coding
Guidelines rule CP25 *Data types conversion should be explicit*.

Untyped literals take the type of the other operand (`nInt + 1` stays INT, `MAX(0.0, rSpeed)` stays REAL), so they
never cause a finding by themselves; named constants count by value, and one that fits is fine. Conversions between
signed and unsigned types of the same size, and from integers to reals, are not reported. Rockwell code is not
checked: Logix converts on assignment by design and has no conversion functions to make it explicit.

## Noncompliant

```st
FUNCTION_BLOCK FB_Count
VAR_INPUT
    diPulses : DINT;
    rSpeed : REAL;
END_VAR
VAR
    nPulses : INT;
    nSpeed : INT;
END_VAR
nPulses := diPulses;
nSpeed := rSpeed;
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Count
VAR_INPUT
    diPulses : DINT;
    rSpeed : REAL;
END_VAR
VAR
    nPulses : INT;
    nSpeed : INT;
END_VAR
nPulses := DINT_TO_INT(LIMIT(-32768, diPulses, 32767));
nSpeed := REAL_TO_INT(rSpeed);
END_FUNCTION_BLOCK
```
