PROGRAM P_Conveyor
VAR
    fbDelay : TON;
    fbEdge : R_TRIG;
    fbMotor : FB_Motor;
    afbAxis : ARRAY[0..3] OF FB_Motor;
    bStart, bStop, bRun : BOOL;
    i : INT;
END_VAR
fbDelay(IN := bStart, PT := T#2S);
IF bRun THEN
    fbDelay(IN := bStop, PT := T#5S); // PLCA-P003
END_IF
fbEdge(CLK := bStart);
fbEdge(CLK := bStop); // PLCA-P003
afbAxis[1]();
afbAxis[1](); // PLCA-P003
afbAxis[i]();
afbAxis[i]();
IF bStart THEN
    fbMotor();
ELSIF bStop THEN
    fbMotor();
ELSE
    fbMotor();
END_IF
CASE i OF
    1: fbEdge(CLK := bRun); // PLCA-P003
    2: fbMotor(); // PLCA-P003
END_CASE
END_PROGRAM

FUNCTION_BLOCK FB_Motor
END_FUNCTION_BLOCK
