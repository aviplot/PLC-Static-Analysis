PROGRAM P_L010
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 AND n > 5 THEN // PLCA-L010
    q := TRUE;
END_IF
q := n > 10 OR n > 5; // PLCA-L010
q := b AND c AND b; // PLCA-L010
q := (b OR c) AND b; // PLCA-L010
END_PROGRAM
