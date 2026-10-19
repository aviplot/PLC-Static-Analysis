PROGRAM P_Ok
VAR
    fbStartDelay : TON;
    fbStopDelay : TON;
    bStart, bStop, bRun : BOOL;
END_VAR
IF bRun THEN
    fbStartDelay(IN := bStart, PT := T#2S);
ELSE
    fbStartDelay(IN := FALSE, PT := T#2S);
END_IF
fbStopDelay(IN := bStop, PT := T#5S);
END_PROGRAM
