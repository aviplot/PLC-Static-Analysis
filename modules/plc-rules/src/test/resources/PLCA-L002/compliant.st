FUNCTION F_Next : INT
F_Next := 1;
END_FUNCTION

PROGRAM P_L002ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 20 THEN
    q := TRUE;
ELSIF n > 10 THEN
    q := FALSE;
END_IF
IF F_Next() > 10 THEN
    q := TRUE;
ELSIF F_Next() > 20 THEN
    q := FALSE;
END_IF
IF r > 1.0 THEN
    q := TRUE;
ELSIF r > 0.5 THEN
    q := FALSE;
END_IF
END_PROGRAM
