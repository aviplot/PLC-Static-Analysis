FUNCTION_BLOCK FB_InOut
VAR_IN_OUT
    io : INT;
END_VAR
VAR
    x : INT;
    q : BOOL;
END_VAR
IF io > 10 THEN
    x := 1;
    IF io < 5 THEN
        q := TRUE;
    END_IF
END_IF
IF io > 10 THEN
    IF io < 5 THEN // PLCA-L001
        q := TRUE;
    END_IF
END_IF
END_FUNCTION_BLOCK
