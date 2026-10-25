PROGRAM P_L018ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n < 10 THEN
    ; // nothing below the limit: the pump keeps its state
ELSE
    q := TRUE;
END_IF
IF n >= 10 THEN
    q := TRUE;
END_IF
END_PROGRAM
