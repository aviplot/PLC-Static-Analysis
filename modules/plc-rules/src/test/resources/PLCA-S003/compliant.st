PROGRAM P_Normal
VAR
    fbClock2 : RTC;
    bLight : BOOL;
    nState : DINT;
    fbDelay : TON;
END_VAR
fbClock2(EN := TRUE);
bLight := DT_TO_TOD(fbClock2.CDT) >= TOD#18:00:00;
IF nState = 16#8000 THEN
    nState := 0;
END_IF
fbDelay(IN := TRUE, PT := T#10S);
END_PROGRAM
