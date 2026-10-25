PROGRAM P_L022
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    aData : ARRAY[1..10] OF INT;
END_VAR
FOR n := 1 TO 10 DO // PLCA-L022
    IF aData[n] > 100 THEN
        m := n;
    END_IF
    EXIT;
END_FOR
WHILE b DO // PLCA-L022
    m := m + 1;
    RETURN;
END_WHILE
END_PROGRAM
