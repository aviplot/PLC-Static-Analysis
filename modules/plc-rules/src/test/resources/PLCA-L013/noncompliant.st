PROGRAM P_L013
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 THEN // PLCA-L013
    q := TRUE;
ELSE
    q := TRUE;
END_IF
IF n > 10 THEN
    q := TRUE;
ELSIF n > 5 THEN // PLCA-L013
    q := TRUE;
ELSE
    q := FALSE;
END_IF
IF n > 10 THEN
    q := FALSE;
ELSIF n > 5 THEN
    q := TRUE;
ELSE // PLCA-L013
    q := TRUE;
END_IF
CASE n OF
    10: q := TRUE; m := 0;
    20: q := FALSE;
    30: q := TRUE; m := 0; // PLCA-L013
END_CASE
END_PROGRAM
