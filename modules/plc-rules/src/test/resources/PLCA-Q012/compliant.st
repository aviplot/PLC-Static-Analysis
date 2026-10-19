FUNCTION_BLOCK FB_Ok
VAR_INPUT
    bReady : BOOL;
    pData : POINTER TO INT;
END_VAR
VAR
    nTries : INT;
    i : INT;
    fbInner : FB_Inner;
END_VAR
WHILE NOT bReady AND nTries < 10 DO
    nTries := nTries + 1;
END_WHILE
WHILE TRUE DO
    nTries := nTries + 1;
    IF nTries > 100 THEN
        EXIT;
    END_IF
END_WHILE
REPEAT
    i := i + 1;
UNTIL i > 10
END_REPEAT
WHILE pData^ > 0 DO
    nTries := nTries + 1;
END_WHILE
WHILE NOT fbInner.bDone DO
    fbInner();
END_WHILE
WHILE F_Busy() DO
    nTries := nTries + 1;
END_WHILE
END_FUNCTION_BLOCK

FUNCTION_BLOCK FB_Inner
VAR_OUTPUT
    bDone : BOOL;
END_VAR
bDone := TRUE;
END_FUNCTION_BLOCK

FUNCTION F_Busy : BOOL
F_Busy := FALSE;
END_FUNCTION
