PROGRAM P_L019
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 THEN // PLCA-L019
    q := TRUE;
ELSE
    q := FALSE;
END_IF
IF b AND c THEN // PLCA-L019
    q := FALSE;
ELSE
    q := TRUE;
END_IF
END_PROGRAM
