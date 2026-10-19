FUNCTION_BLOCK FB_Deep
VAR_INPUT
    a, b, c, d, e, f : BOOL;
    n : INT;
END_VAR
VAR_OUTPUT
    x : INT;
END_VAR
IF a THEN
    IF b THEN
        IF c THEN
            IF d THEN
                IF e THEN
                    WHILE f DO // PLCA-Q013
                        x := 1;
                        f := FALSE;
                    END_WHILE
                END_IF
            END_IF
        END_IF
    END_IF
END_IF
END_FUNCTION_BLOCK

FUNCTION F_Branchy : INT // PLCA-Q013
VAR_INPUT
    n : INT;
END_VAR
CASE n OF
    1: F_Branchy := 1;
    2: F_Branchy := 2;
    3: F_Branchy := 3;
    4: F_Branchy := 4;
    5: F_Branchy := 5;
    6: F_Branchy := 6;
    7: F_Branchy := 7;
    8: F_Branchy := 8;
    9: F_Branchy := 9;
    10: F_Branchy := 10;
END_CASE
IF n > 100 THEN F_Branchy := 0; ELSIF n > 50 THEN F_Branchy := 1; ELSIF n > 40 THEN F_Branchy := 2; END_IF
IF n > 1 THEN F_Branchy := 0; ELSIF n > 2 THEN F_Branchy := 1; ELSIF n > 3 THEN F_Branchy := 2; END_IF
IF n > 4 THEN F_Branchy := 0; ELSIF n > 5 THEN F_Branchy := 1; ELSIF n > 6 THEN F_Branchy := 2; END_IF
FOR n := 0 TO 2 DO END_FOR
END_FUNCTION
