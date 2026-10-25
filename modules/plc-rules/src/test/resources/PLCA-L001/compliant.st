FUNCTION_BLOCK FB_Set
VAR_INPUT
    x : INT;
END_VAR
gnShared := x;
END_FUNCTION_BLOCK

VAR_GLOBAL
    gnShared : INT;
END_VAR

PROGRAM P_L001ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    p : POINTER TO INT;
    fbSet : FB_Set;
    fbTimer : TON;
END_VAR
VAR CONSTANT
    cMode : INT := 1;
END_VAR
IF n > 100 THEN
    n := 0;
    IF n < 50 THEN
        q := TRUE;
    END_IF
END_IF
IF gnShared > 100 THEN
    fbSet(x := 1);
    IF gnShared < 50 THEN
        q := TRUE;
    END_IF
END_IF
p := ADR(m);
IF m > 100 THEN
    p^ := 0;
    IF m < 50 THEN
        q := TRUE;
    END_IF
END_IF
IF fbTimer.Q THEN
    fbTimer(IN := b, PT := T#1S);
    IF NOT fbTimer.Q THEN
        q := TRUE;
    END_IF
END_IF
IF cMode = 1 THEN
    IF cMode = 2 THEN
        q := TRUE;
    END_IF
END_IF
IF n > 10 THEN
    WHILE n < 5 DO
        n := n + 1;
    END_WHILE
END_IF
END_PROGRAM
