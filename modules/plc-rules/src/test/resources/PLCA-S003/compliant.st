PROGRAM P_Normal
VAR
    fbClock2 : RTC;
    bLight : BOOL;
    nState : DINT;
    fbDelay : TON;
    fbNever : TON;
    fbWeekly : TON;
    dtLast : DT;
END_VAR
fbClock2(EN := TRUE);
bLight := DT_TO_TOD(fbClock2.CDT) >= TOD#18:00:00;
IF nState = 16#8000 THEN
    nState := 0;
END_IF
fbDelay(IN := TRUE, PT := T#10S);
IF dtLast = DT#1970-01-01-00:00:00 THEN
    nState := 1;
END_IF
fbNever(IN := TRUE, PT := T#49D17H2M47S295MS);
fbWeekly(IN := TRUE, PT := T#7D);
IF fbWeekly.Q THEN
    fbWeekly(IN := FALSE);
END_IF
END_PROGRAM
