---
id: PLCA-P004
title: Global variable written from several tasks
severity: warning
category: plc
dialects: all
tags: [reliability, concurrency]
plcopen: CP10
cwe: CWE-362
parameters:
  ignorePattern: regex =  | Names of globals that are written from several tasks by design (e.g. diagnostics counters); never reported
  startupTaskPattern: regex = (?i)^(startup|(complete|warm|cold)[ _]?restart|ob10[0-2])$ | Names of startup tasks (Siemens OB100–OB102), which finish before cyclic tasks start and so do not count
---
## Description

A global variable is written by code running in more than one task, according to the project's task configuration
(IEC configurations, TwinCAT task objects, PLCopen instances, Rockwell tasks).

## Rationale

Tasks of different priority interrupt each other. If two tasks write the same global, one task can preempt the other
between reading and writing it, and an update is lost; a structure or string can be seen half written. PLCopen Coding
Guidelines rule CP10 *Avoid multiple writes from multiple tasks*. Let one task own the variable and hand data over
through separate variables, or use the runtime's synchronisation (CODESYS `SysSem`, TwinCAT `TestAndSet`).

The rule needs a task configuration with at least two tasks; without one it is silent. A POU runs in every task whose
programs call it, directly or indirectly; Siemens organisation blocks are tasks. Startup tasks (`startupTaskPattern`,
by default the TIA names of OB100–OB102) do not count, since they finish before the cyclic tasks start. One finding per
variable.

## Noncompliant

```st
VAR_GLOBAL
    gnParts : INT;
END_VAR

PROGRAM P_Fast
gnParts := gnParts + 1;
END_PROGRAM

PROGRAM P_Slow
gnParts := 0;
END_PROGRAM

CONFIGURATION Cfg
    RESOURCE Res ON PLC
        TASK tFast(INTERVAL := T#2MS, PRIORITY := 1);
        TASK tSlow(INTERVAL := T#100MS, PRIORITY := 10);
        PROGRAM iFast WITH tFast : P_Fast;
        PROGRAM iSlow WITH tSlow : P_Slow;
    END_RESOURCE
END_CONFIGURATION
```

## Compliant

```st
VAR_GLOBAL
    gnParts : INT;
    gbResetParts : BOOL;
END_VAR

PROGRAM P_Fast
IF gbResetParts THEN
    gnParts := 0;
ELSE
    gnParts := gnParts + 1;
END_IF
END_PROGRAM

PROGRAM P_Slow
gbResetParts := TRUE;
END_PROGRAM

CONFIGURATION Cfg
    RESOURCE Res ON PLC
        TASK tFast(INTERVAL := T#2MS, PRIORITY := 1);
        TASK tSlow(INTERVAL := T#100MS, PRIORITY := 10);
        PROGRAM iFast WITH tFast : P_Fast;
        PROGRAM iSlow WITH tSlow : P_Slow;
    END_RESOURCE
END_CONFIGURATION
```
