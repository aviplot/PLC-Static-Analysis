PROGRAM P_L015ok
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
ELSIF b THEN
    q := FALSE;
END_IF
IF n > 10 THEN
    q := TRUE;
ELSE
    m := 0;
    IF b THEN
        q := FALSE;
    END_IF
END_IF
IF n > 10 THEN
    q := TRUE;
ELSE
    // the alarm case is handled separately
    IF b THEN
        q := FALSE;
    END_IF
END_IF
END_PROGRAM
