---
id: PLCA-L025
title: BOOL compared with TRUE or FALSE
severity: info
category: logic
dialects: all
tags: [style]
plcopen: -
enabled: false
---
## Description

`b = TRUE` is `b`, `b = FALSE` is `NOT b`.

## Rationale

The comparison adds nothing; the BOOL is already the condition. Off by default.

## Noncompliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF bAlarm = TRUE THEN
    bPump := FALSE;
END_IF
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Test
VAR
    nLevel : INT;
    bPump : BOOL;
    bAlarm : BOOL;
END_VAR
IF bAlarm THEN
    bPump := FALSE;
END_IF
END_PROGRAM
```
