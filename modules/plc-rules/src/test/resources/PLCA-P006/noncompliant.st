PROGRAM P_Jump
VAR
    nStep : INT;
END_VAR
IF nStep = 0 THEN
    JMP lEnd; // PLCA-P006
END_IF
nStep := nStep + 1;
lEnd:
END_PROGRAM
