FUNCTION_BLOCK FB_Wait
VAR_INPUT
    bReady : BOOL;
END_VAR
VAR
    nTries : INT;
    i : INT;
END_VAR
WHILE NOT bReady DO // PLCA-Q012
    nTries := nTries + 1;
END_WHILE
WHILE TRUE DO // PLCA-Q012
    nTries := nTries + 1;
    FOR i := 0 TO 3 DO
        EXIT;
    END_FOR
END_WHILE
REPEAT // PLCA-Q012
    nTries := nTries + 1;
UNTIL FALSE
END_REPEAT
REPEAT // PLCA-Q012
    nTries := 0;
UNTIL i > 10
END_REPEAT
END_FUNCTION_BLOCK
