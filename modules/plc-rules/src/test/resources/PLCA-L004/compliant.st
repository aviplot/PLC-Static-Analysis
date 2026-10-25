FUNCTION F_Next : INT
F_Next := 1;
END_FUNCTION

PROGRAM P_L004ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    sName : STRING(10);
END_VAR
VAR CONSTANT
    cOn : BOOL := TRUE;
END_VAR
IF n = 1 OR n = 2 THEN
    q := TRUE;
END_IF
q := r <> r;
q := F_Next() > 10 AND F_Next() < 5;
q := cOn AND NOT cOn;
q := n > 10 AND m < 5;
q := sName = 'A' OR sName = 'a';
END_PROGRAM
