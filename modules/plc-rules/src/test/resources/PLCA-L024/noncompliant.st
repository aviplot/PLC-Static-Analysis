PROGRAM P_L024
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
q := NOT (n = 10); // PLCA-L024
IF NOT (n < m) THEN // PLCA-L024
    q := TRUE;
END_IF
END_PROGRAM
