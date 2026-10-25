PROGRAM P_L005
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 5 THEN
    q := TRUE;
ELSIF n < 0 THEN
    q := FALSE;
ELSIF 5 < n THEN // PLCA-L005
    b := TRUE;
END_IF
IF n >= 6 THEN
    q := TRUE;
ELSIF n > 5 THEN // PLCA-L005
    q := FALSE;
END_IF
IF b AND c THEN
    q := TRUE;
ELSIF c AND b THEN // PLCA-L005
    q := FALSE;
END_IF
END_PROGRAM
