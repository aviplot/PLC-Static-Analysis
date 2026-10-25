PROGRAM P_L003
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n < 10 THEN
    q := TRUE;
ELSIF n >= 10 THEN
    q := FALSE;
ELSE // PLCA-L003
    b := TRUE;
END_IF
IF b THEN
    q := TRUE;
ELSIF NOT b THEN
    q := FALSE;
ELSE // PLCA-L003
    c := TRUE;
END_IF
IF n > 0 THEN
    IF n > 5 THEN
        q := TRUE;
    ELSIF n <= 5 THEN
        q := FALSE;
    ELSE // PLCA-L003
        c := TRUE;
    END_IF
END_IF
END_PROGRAM
