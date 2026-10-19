VAR_GLOBAL
    gnParts : INT;
    gnOwned : INT;
    gnShared : INT;
END_VAR

PROGRAM P_Fast
gnParts := gnParts + 1;
gnOwned := 1;
F_Log();
END_PROGRAM

PROGRAM P_Slow
gnParts := 0; // PLCA-P004
F_Log();
END_PROGRAM

FUNCTION F_Log : BOOL
gnShared := gnShared + 1; // PLCA-P004
END_FUNCTION

PROGRAM P_Unscheduled
gnOwned := 2;
END_PROGRAM

CONFIGURATION Cfg
    RESOURCE Res ON PLC
        TASK tFast(INTERVAL := T#2MS, PRIORITY := 1);
        TASK tSlow(INTERVAL := T#100MS, PRIORITY := 10);
        PROGRAM iFast WITH tFast : P_Fast;
        PROGRAM iSlow WITH tSlow : P_Slow;
    END_RESOURCE
END_CONFIGURATION
