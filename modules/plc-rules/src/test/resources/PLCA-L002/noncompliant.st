PROGRAM P_L002
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
ELSIF n > 20 THEN // PLCA-L002
    q := FALSE;
END_IF
IF n > 10 THEN
    q := TRUE;
ELSIF n <= 10 THEN
    q := FALSE;
ELSIF n = 5 THEN // PLCA-L002
    b := TRUE;
END_IF
IF b AND c THEN
    q := TRUE;
ELSIF b THEN
    q := FALSE;
ELSIF b AND c AND n > 0 THEN // PLCA-L002
    q := TRUE;
END_IF
END_PROGRAM
