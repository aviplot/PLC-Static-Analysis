---
id: PLCA-Q005
title: FOR loop variable modified in the loop body
severity: warning
category: quality
dialects: all
tags: [reliability, loops]
plcopen: L12
---
## Description

The control variable of a FOR loop is assigned inside the loop body, passed to an output or VAR_IN_OUT parameter, or
reused as the control variable of a nested FOR.

## Rationale

IEC 61131-3 says the control variable shall not be altered by the repeated statements. Changing it makes the number
of iterations unpredictable and can cause an endless loop, which trips the task watchdog. Use WHILE or REPEAT when
the iteration count is not known in advance. PLCopen Coding Guidelines rule *Loop variables should not be modified
inside a FOR loop*: the rule section says L12, the document's table of contents says L22.

## Noncompliant

```st
FUNCTION F_FindFirst : INT
VAR_INPUT
    aValues : ARRAY[1..10] OF INT;
END_VAR
VAR
    i : INT;
END_VAR
F_FindFirst := 0;
FOR i := 1 TO 10 DO
    IF aValues[i] > 0 THEN
        F_FindFirst := i;
        i := 10;
    END_IF
END_FOR
END_FUNCTION
```

## Compliant

```st
FUNCTION F_FindFirst : INT
VAR_INPUT
    aValues : ARRAY[1..10] OF INT;
END_VAR
VAR
    i : INT;
END_VAR
F_FindFirst := 0;
FOR i := 1 TO 10 DO
    IF aValues[i] > 0 THEN
        F_FindFirst := i;
        EXIT;
    END_IF
END_FOR
END_FUNCTION
```
