PROGRAM P_L020
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
    m := 0; // PLCA-L020
ELSE
    q := FALSE;
    m := 0;
END_IF
CASE n OF
    1: b := TRUE; // PLCA-L020
       q := TRUE;
    2: b := TRUE;
       q := FALSE;
ELSE
    b := TRUE;
END_CASE
END_PROGRAM
