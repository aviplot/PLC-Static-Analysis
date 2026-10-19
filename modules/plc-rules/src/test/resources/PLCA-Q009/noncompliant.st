FUNCTION F_Clamp : INT
VAR_INPUT
    nValue : INT;
END_VAR
F_Clamp := nValue;
RETURN;
F_Clamp := 0; // PLCA-Q009
END_FUNCTION

FUNCTION_BLOCK FB_Loop
VAR
    i : INT;
    a : ARRAY[0..9] OF INT;
    bDone : BOOL;
END_VAR
FOR i := 0 TO 9 DO
    IF a[i] = 0 THEN
        EXIT;
        a[i] := 1; // PLCA-Q009
    END_IF
END_FOR
IF FALSE THEN // PLCA-Q009
    bDone := TRUE;
END_IF
IF TRUE OR bDone THEN
    bDone := FALSE;
END_IF
IF 1 > 0 THEN // PLCA-Q009
    bDone := TRUE;
ELSE
    bDone := FALSE;
END_IF
WHILE FALSE DO // PLCA-Q009
    i := i + 1;
END_WHILE
IF bDone THEN
    RETURN;
ELSE
    RETURN;
END_IF
i := 0; // PLCA-Q009
END_FUNCTION_BLOCK
