PROGRAM P_L016ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    p : POINTER TO INT;
    aData : ARRAY[1..3] OF INT;
END_VAR
IF p <> 0 THEN
    IF p^ > 10 THEN
        q := TRUE;
    END_IF
END_IF
IF n <= 3 THEN
    IF aData[n] > 0 THEN
        q := TRUE;
    END_IF
END_IF
IF b THEN
    IF n > 10 THEN
        q := TRUE;
    END_IF
ELSE
    q := FALSE;
END_IF
IF b THEN
    m := 1;
    IF n > 10 THEN
        q := TRUE;
    END_IF
END_IF
END_PROGRAM
