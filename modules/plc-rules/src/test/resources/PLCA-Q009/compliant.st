FUNCTION_BLOCK FB_Ok
VAR
    i : INT;
    bDone : BOOL;
END_VAR
VAR CONSTANT
    cSimulation : BOOL := FALSE;
END_VAR
IF cSimulation THEN
    bDone := TRUE;
END_IF
IF bDone THEN
    JMP lEnd;
END_IF
i := i + 1;
lEnd:
i := 0;
IF bDone THEN
    RETURN;
END_IF
i := 2;
END_FUNCTION_BLOCK
