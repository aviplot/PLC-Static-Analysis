PROGRAM P_L015
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 THEN
    q := TRUE;
(* PLCA-L015 *) ELSE
    IF b THEN
        q := FALSE;
    END_IF
END_IF
END_PROGRAM
