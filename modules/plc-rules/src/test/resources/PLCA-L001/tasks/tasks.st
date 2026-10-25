VAR_GLOBAL
    gnMode : INT;
END_VAR

PROGRAM P_Fast
gnMode := gnMode + 1;
END_PROGRAM

PROGRAM P_Slow
VAR
    nLocal : INT;
    q : BOOL;
END_VAR
IF gnMode > 10 THEN
    IF gnMode < 5 THEN // PLCA-L001 (info: P_Fast writes gnMode)
        q := TRUE;
    END_IF
END_IF
IF nLocal > 10 THEN
    IF nLocal < 5 THEN // PLCA-L001
        q := TRUE;
    END_IF
END_IF
END_PROGRAM

CONFIGURATION Cfg
    RESOURCE Res ON PLC
        TASK tFast(INTERVAL := T#2MS, PRIORITY := 1);
        TASK tSlow(INTERVAL := T#100MS, PRIORITY := 10);
        PROGRAM iFast WITH tFast : P_Fast;
        PROGRAM iSlow WITH tSlow : P_Slow;
    END_RESOURCE
END_CONFIGURATION
