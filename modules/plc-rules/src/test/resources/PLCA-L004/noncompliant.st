PROGRAM P_L004
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    sName : STRING(10);
END_VAR
IF n = 1 AND n = 2 THEN // PLCA-L004
    q := TRUE;
END_IF
q := n > 10 AND n < 5; // PLCA-L004
q := b AND c AND NOT b; // PLCA-L004
WHILE n <> n DO // PLCA-L004
    n := n + 1;
END_WHILE
q := (n > 10 AND m > 3) AND (n < 0 OR n < 5); // PLCA-L004
q := sName = 'A' AND sName = 'a'; // PLCA-L004 (texts compare case-sensitively)
END_PROGRAM
