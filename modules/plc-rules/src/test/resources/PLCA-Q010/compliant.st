FUNCTION F_Ok : INT
VAR_INPUT
    bMode : BOOL;
    n : INT;
END_VAR
VAR
    nPick : INT;
    nInit : INT := 5;
    nBoth : INT;
    nCase : INT;
    nRep : INT;
    nOut : INT;
    nAdr : INT;
    p : POINTER TO INT;
    stData : ST_Data;
END_VAR
IF bMode THEN
    nBoth := 1;
ELSE
    nBoth := 2;
END_IF
CASE n OF
    1: nCase := 1;
    ELSE nCase := 0;
END_CASE
REPEAT
    nRep := 1;
UNTIL TRUE
END_REPEAT
IF n < 0 THEN
    RETURN;
ELSE
    nPick := n;
END_IF
F_Out(n := n, nOut => nOut);
p := ADR(nAdr);
F_Ok := nPick + nInit + nBoth + nCase + nOut + nAdr + stData.a;
END_FUNCTION

FUNCTION F_Out : BOOL
VAR_INPUT
    n : INT;
END_VAR
VAR_OUTPUT
    nOut : INT;
END_VAR
nOut := n;
END_FUNCTION

TYPE ST_Data :
STRUCT
    a : INT;
END_STRUCT
END_TYPE

FUNCTION_BLOCK FB_Static
VAR
    nCount : INT;
END_VAR
nCount := nCount + 1;
END_FUNCTION_BLOCK
