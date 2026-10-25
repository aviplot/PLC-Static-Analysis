PROGRAM P_L001
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 100 THEN
    IF n < 50 THEN // PLCA-L001
        q := TRUE;
    END_IF
END_IF
IF n > 10 THEN
    q := TRUE;
ELSE
    IF n > 20 THEN // PLCA-L001
        q := FALSE;
    END_IF
END_IF
IF m < 0 THEN
    RETURN;
END_IF
IF m < -5 THEN // PLCA-L001
    q := TRUE;
END_IF
IF n > 10 THEN
    CASE n OF
        5: q := TRUE; // PLCA-L001
        20: q := FALSE;
    ELSE
        q := b;
    END_CASE
END_IF
IF b AND n = 3 THEN
    WHILE n > 7 DO // PLCA-L001
        m := m + 1;
    END_WHILE
END_IF
END_PROGRAM
