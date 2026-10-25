PROGRAM P_L008ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 THEN
    n := n - 20;
    IF n > 5 THEN
        q := TRUE;
    END_IF
END_IF
FOR m := 1 TO 10 DO
    IF n > 10 THEN
        q := TRUE;
    END_IF
    n := n + 1;
END_FOR
IF n > 10 THEN
    IF n > 50 THEN
        q := TRUE;
    END_IF
END_IF
END_PROGRAM
