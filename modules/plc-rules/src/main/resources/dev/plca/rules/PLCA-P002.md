---
id: PLCA-P002
title: Direct addressing in code
severity: info
category: plc
dialects: all
tags: [plc, portability, readability]
plcopen: N1
---
## Description

A direct address such as `%IX0.1`, `%QW4` or `%MD10` is used in code instead of a symbolic variable.

## Rationale

A bare address says nothing about what it is, makes the code depend on one hardware layout, and is easily missed when
the I/O configuration changes. Declare a variable with `AT %…` once and use its name. PLCopen Coding Guidelines rule
N1 *Avoid physical addresses*.

Siemens SCL: absolute operands (`%I0.0`, `%DB1.DBX0.0`, classic `DB10.DBW2`) and ANY pointers (`P#DB1.DBX0.0 BYTE
10`) are reported; use a PLC tag (`"Start_Button"`) or a data block member (`"DB_IO".Valve`) instead.

Rockwell (L5X) has no direct addresses; the equivalent is logic on raw module tags (`Local:1:I.Data.5`,
`Rack1:2:O.Data[0]`). They are reported except in routines that only map I/O (every rung a contact driving a coil, a
`MOV`/`COP`, or every ST statement a plain assignment) and when a whole module tag is handed to a device AOI
(`PF755_Drive(Pump, Pump:I, Pump:O, …)`), the device-object form Rockwell recommends. Alias tags of module points are
the compliant form. Controller status flags (`S:FS`) are not I/O.

## Noncompliant

```st
PROGRAM P_Lamp
%QX0.1 := %IX0.0;
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Lamp
VAR
    iButton AT %IX0.0 : BOOL;
    qLamp AT %QX0.1 : BOOL;
END_VAR
qLamp := iButton;
END_PROGRAM
```
