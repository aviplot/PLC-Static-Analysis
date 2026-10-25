PROGRAM P_L009
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
ELSIF n <= 10 THEN // PLCA-L009
    q := FALSE;
END_IF
IF n > m THEN
    q := TRUE;
ELSIF m >= n THEN // PLCA-L009
    q := FALSE;
END_IF
END_PROGRAM
