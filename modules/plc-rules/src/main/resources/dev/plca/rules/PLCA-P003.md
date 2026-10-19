---
id: PLCA-P003
title: Function block instance called more than once per cycle
severity: warning
category: plc
dialects: all
tags: [reliability, timers]
plcopen: CP20
---
## Description

The same function block instance — a timer, counter, edge detector or user FB — is called more than once in a cycle:
twice in one body (not as alternatives in different IF/CASE branches), or from two programs, function blocks or
routines when the instance is global. Rockwell: the same TIMER in two TON/TOF/RTO instructions, or the same COUNTER in
two CTU (or two CTD) instructions.

## Rationale

A function block keeps state between calls and expects to be called once per cycle. A second call works on what the
first left behind: a timer called with two different IN values restarts or never elapses, an R_TRIG sees no edge, a
counter counts twice. In ladder the second timer instruction runs even when its rung is false, and resets the timer.
PLCopen Coding Guidelines rule CP20 *Function block instances should be called only once*. Call the instance once and
use its outputs where needed.

Not compared: calls inside loops, calls through a variable array index (`afbAxis[i]()`), and calls in SFC actions,
where the active step decides what runs. CTU and CTD on the same Rockwell counter form an up/down counter and are not
reported.

## Noncompliant

```st
PROGRAM P_Conveyor
VAR
    fbDelay : TON;
    bStart, bStop, bRun : BOOL;
END_VAR
fbDelay(IN := bStart, PT := T#2S);
IF fbDelay.Q THEN
    bRun := TRUE;
END_IF
fbDelay(IN := bStop, PT := T#5S);
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Conveyor
VAR
    fbStartDelay : TON;
    fbStopDelay : TON;
    bStart, bStop, bRun : BOOL;
END_VAR
fbStartDelay(IN := bStart, PT := T#2S);
fbStopDelay(IN := bStop, PT := T#5S);
IF fbStartDelay.Q THEN
    bRun := TRUE;
END_IF
END_PROGRAM
```
