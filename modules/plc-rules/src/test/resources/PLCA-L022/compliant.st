PROGRAM P_L022ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    aData : ARRAY[1..10] OF INT;
END_VAR
FOR n := 1 TO 10 DO
    IF aData[n] > 100 THEN
        m := n;
        EXIT;
    END_IF
END_FOR
WHILE TRUE DO
    IF NOT b THEN
        EXIT;
    END_IF
    m := m + 1;
    IF m > 5 THEN
        EXIT;
    END_IF
    q := TRUE;
    EXIT;
END_WHILE
FOR n := 1 TO 10 DO
    IF aData[n] = 0 THEN
        CONTINUE;
    END_IF
    m := n;
    EXIT;
END_FOR
END_PROGRAM
