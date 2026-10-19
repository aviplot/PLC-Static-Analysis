PROGRAM MAIN
VAR
    fbPump : FB_Pump;
    rLevel : REAL;
    bAlarm : BOOL;
END_VAR
fbPump(bRun := TRUE);
bAlarm := rLevel = 2.5;
END_PROGRAM
