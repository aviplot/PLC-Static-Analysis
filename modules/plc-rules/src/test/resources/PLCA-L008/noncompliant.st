PROGRAM P_L008
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 THEN
    IF n > 5 THEN // PLCA-L008
        q := TRUE;
    END_IF
END_IF
IF m <= 0 THEN
    RETURN;
END_IF
IF m > 0 THEN // PLCA-L008
    q := TRUE;
END_IF
IF b THEN
    m := 1;
    IF b AND n > 10 THEN
        IF b THEN // PLCA-L008
            q := TRUE;
        ELSE
            q := FALSE;
        END_IF
    END_IF
END_IF
END_PROGRAM
