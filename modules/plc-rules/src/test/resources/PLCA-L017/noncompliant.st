PROGRAM P_L017
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF b THEN
    RETURN;
ELSE // PLCA-L017
    q := TRUE;
END_IF
FOR n := 1 TO 10 DO
    IF b THEN
        q := TRUE;
        CONTINUE;
    ELSIF c THEN
        EXIT;
    ELSE // PLCA-L017
        m := n;
    END_IF
END_FOR
END_PROGRAM
