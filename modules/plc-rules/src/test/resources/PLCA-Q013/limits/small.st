FUNCTION F_Two : INT // PLCA-Q013
VAR_INPUT
    a, b, c : BOOL;
END_VAR
IF a THEN
    IF b THEN
        IF c THEN // PLCA-Q013
            F_Two := 1;
        END_IF
    END_IF
END_IF
END_FUNCTION
