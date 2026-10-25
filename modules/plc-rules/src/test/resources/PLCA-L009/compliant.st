PROGRAM P_L009ok
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
IF r > 1.0 THEN
    q := TRUE;
ELSIF r < 1.0 THEN
    q := FALSE;
END_IF
END_PROGRAM
