FUNCTION F_Next : INT
F_Next := 1;
END_FUNCTION

PROGRAM P_L005ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF r >= 6.0 THEN
    q := TRUE;
ELSIF r > 5.0 THEN
    q := FALSE;
END_IF
IF F_Next() > 5 THEN
    q := TRUE;
ELSIF F_Next() > 5 THEN
    q := FALSE;
END_IF
END_PROGRAM
