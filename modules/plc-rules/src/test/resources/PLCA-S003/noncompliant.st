PROGRAM P_Bomb
VAR
    fbClock : RTC;
    fbStart : TON;
    fbLong : TON;
    bRun : BOOL;
    nCode : DINT;
    dtLimit : DT;
    stNow : SYSTEMTIME;
END_VAR
fbClock(EN := TRUE);
IF fbClock.CDT >= DT#2027-01-01-00:00:00 THEN // PLCA-S003
    bRun := FALSE;
END_IF
IF nCode = 16#DEADBEEF THEN // PLCA-S003
    bRun := TRUE;
END_IF
IF nCode = 31337 THEN // PLCA-S003
    bRun := TRUE;
END_IF
fbStart(IN := TRUE, PT := T#5S);
fbLong(IN := TRUE, PT := T#30D); // PLCA-S003
IF fbLong.Q AND fbStart.Q THEN
    bRun := FALSE;
END_IF
END_PROGRAM

TYPE SYSTEMTIME :
STRUCT
    wYear : WORD;
END_STRUCT
END_TYPE
