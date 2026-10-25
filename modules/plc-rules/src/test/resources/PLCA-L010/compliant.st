FUNCTION F_Next : INT
F_Next := 1;
END_FUNCTION

PROGRAM P_L010ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 AND n < 50 THEN
    q := TRUE;
END_IF
q := F_Next() > 10 AND F_Next() > 5;
q := b AND c;
q := (b OR c) AND NOT b;
END_PROGRAM
