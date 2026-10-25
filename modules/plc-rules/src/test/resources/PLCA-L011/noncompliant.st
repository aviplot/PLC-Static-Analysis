PROGRAM P_L011
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n < 100 THEN // PLCA-L011
    q := TRUE;
ELSIF n > 100 THEN
    q := FALSE;
END_IF
IF n > m THEN // PLCA-L011
    q := TRUE;
ELSIF n < m THEN
    q := FALSE;
END_IF
IF r < 0.0 THEN // PLCA-L011
    q := TRUE;
ELSIF r > 0.0 THEN
    q := FALSE;
END_IF
END_PROGRAM
