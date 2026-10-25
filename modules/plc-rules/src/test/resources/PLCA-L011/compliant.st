PROGRAM P_L011ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n < 100 THEN
    q := TRUE;
ELSIF n >= 100 THEN
    q := FALSE;
END_IF
IF n < 0 THEN
    q := TRUE;
ELSIF n > 10 THEN
    q := FALSE;
END_IF
IF n < 0 THEN
    q := TRUE;
ELSIF n > 0 THEN
    q := FALSE;
ELSE
    b := TRUE;
END_IF
IF n <> 0 THEN
    IF n < 0 THEN
        q := TRUE;
    ELSIF n > 0 THEN
        q := FALSE;
    END_IF
END_IF
IF n = 1 THEN
    q := TRUE;
ELSIF n = 2 THEN
    q := FALSE;
END_IF
END_PROGRAM
