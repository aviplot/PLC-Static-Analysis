PROGRAM P_L013ok
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
ELSE
    q := FALSE;
END_IF
IF n > 10 THEN
    q := TRUE;
ELSIF n > 5 THEN
    q := FALSE;
ELSIF n > 0 THEN
    q := TRUE;
END_IF
CASE n OF
    10: ;
    20: ;
END_CASE
END_PROGRAM
