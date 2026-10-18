FUNCTION F_Inc : BOOL
VAR_IN_OUT
    nVal : INT;
END_VAR
nVal := nVal + 1;
END_FUNCTION

FUNCTION_BLOCK FB_Loop
VAR
    i : INT;
    a : ARRAY[0..9] OF INT;
END_VAR
FOR i := 0 TO 9 DO
    IF a[i] = 0 THEN
        i := i + 1; // PLCA-Q005
    END_IF
    FOR i := 0 TO 2 DO // PLCA-Q005
        a[i] := 0;
    END_FOR
    F_Inc(nVal := i); // PLCA-Q005
END_FOR
END_FUNCTION_BLOCK
