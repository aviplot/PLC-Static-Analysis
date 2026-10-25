PROGRAM P_L007
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 5 OR n <= 5 THEN // PLCA-L007
    q := TRUE;
END_IF
q := b OR c OR NOT b; // PLCA-L007
q := n = n; // PLCA-L007
q := NOT (b AND NOT b); // PLCA-L007
END_PROGRAM
