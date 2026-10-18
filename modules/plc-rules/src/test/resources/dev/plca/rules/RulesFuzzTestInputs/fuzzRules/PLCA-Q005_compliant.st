FUNCTION_BLOCK FB_LoopOk
VAR
    i : INT;
    j : INT;
    a : ARRAY[0..9] OF INT;
END_VAR
FOR i := 0 TO 9 DO
    a[i] := i;
    j := i * 2;
    FOR j := 0 TO 1 DO
        a[j] := 0;
    END_FOR
END_FOR
i := 0;
END_FUNCTION_BLOCK
