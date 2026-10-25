---
id: PLCA-L022
title: Loop that ends in its first pass
severity: warning
category: logic
dialects: all
tags: [correctness]
plcopen: -
---
## Description

A FOR, WHILE or REPEAT whose body always ends with EXIT or RETURN: it runs at most once.

## Rationale

Usually the EXIT was meant to sit inside an IF (stop at the first match), or the loop is left over from a
change. Not reported when the body has CONTINUE, a conditional EXIT besides the last one (a loop used as a block that
can be left early), or labels and jumps.

## Noncompliant

```st
PROGRAM P_Test
VAR
    i : INT;
    aValues : ARRAY[1..10] OF INT;
    nFound : INT;
END_VAR
FOR i := 1 TO 10 DO
    IF aValues[i] > 100 THEN
        nFound := i;
    END_IF
    EXIT;
END_FOR
END_PROGRAM
```

## Compliant

```st
PROGRAM P_Test
VAR
    i : INT;
    aValues : ARRAY[1..10] OF INT;
    nFound : INT;
END_VAR
FOR i := 1 TO 10 DO
    IF aValues[i] > 100 THEN
        nFound := i;
        EXIT;
    END_IF
END_FOR
END_PROGRAM
```
