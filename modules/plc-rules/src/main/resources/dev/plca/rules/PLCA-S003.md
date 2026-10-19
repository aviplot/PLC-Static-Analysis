---
id: PLCA-S003
title: Suspicious hidden trigger
severity: warning
category: security
dialects: all
tags: [security, logic-bomb, review]
cwe: CWE-511
parameters:
  minDelay: string = T#24h | Shortest fixed delay after start-up (a timer with IN := TRUE) that is reported
---
## Description

Logic that waits for a moment or a value nobody sets by accident: a date compared to be on or after an absolute date,
a real-time clock value compared to be on or after a calendar year, an equality comparison with a "magic" constant (hexspeak such as `16#DEADBEEF`,
`16#C0FFEE`, or leet numbers such as 1337), or a timer that runs from start-up and fires only after a long delay.

## Rationale

This is the shape of a logic bomb or a backdoor: code that behaves normally during commissioning and tests, and does
something else on a given date, after weeks of operation, or when a value only an insider knows is written. Such code
has been found in sabotage and research cases, and a code review is the best place to catch it. Each finding asks a
reviewer to confirm what the condition enables. Not reported, because they are normal: daily schedules (time-of-day
comparisons), "before" comparisons (a clock that is not set yet), dates up to the year 2000 (the 1970 epoch and S7's
`DT#1990-01-01` mean "not set") and from 2100 (range bounds, the end of DT in 2106), comparisons inside test
assertions, short start-up delays, timers the POU also resets (`tmr(IN := FALSE)`, a periodic cycle) and the 32-bit
TIME maxima (`T#49D17H2M47S295MS`, `T#24D20H31M23S647MS`, `T#24D`), the usual "no timeout" values.

Clock sources recognised: the IEC `RTC` block, CODESYS `SysTimeRtcGet`, TwinCAT `NT_GetTime`, `FB_LocalSystemTime`,
`F_GetSystemTime`, Siemens `RD_LOC_T`/`RD_SYS_T`, and Rockwell `GSV(WallClockTime, …)`.

## Noncompliant

```st
PROGRAM P_Pump
VAR
    fbClock : RTC;
    bRun : BOOL;
    nCode : DINT;
END_VAR
fbClock(EN := TRUE);
IF fbClock.CDT >= DT#2027-01-01-00:00:00 THEN
    bRun := FALSE;
END_IF
IF nCode = 16#DEADBEEF THEN
    bRun := TRUE;
END_IF
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Pump
VAR
    fbClock : RTC;
    bRun : BOOL;
    bNight : BOOL;
END_VAR
fbClock(EN := TRUE);
bNight := DT_TO_TOD(fbClock.CDT) >= TOD#22:00:00;
bRun := NOT bNight;
END_PROGRAM
```
