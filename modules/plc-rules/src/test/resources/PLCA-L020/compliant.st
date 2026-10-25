PROGRAM P_L020ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 THEN
    n := 0;
    q := TRUE;
ELSE
    n := 0;
    q := FALSE;
END_IF
IF n > 10 THEN
    q := TRUE;
    m := 0;
ELSIF n > 5 THEN
    q := FALSE;
    m := 0;
END_IF
IF n > 10 THEN
    q := TRUE;
    m := 0;
ELSE
    q := FALSE;
    m := 1;
END_IF
END_PROGRAM
