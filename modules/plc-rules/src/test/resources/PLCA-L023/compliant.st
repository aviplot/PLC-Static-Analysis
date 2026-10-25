FUNCTION F_L023ok : INT
VAR_INPUT
    nRaw : INT;
    b : BOOL;
END_VAR
VAR
    nTmp : INT;
    p : POINTER TO INT;
    nAlias : INT;
END_VAR
nTmp := nRaw * 2;
nTmp := nTmp + 1;
nTmp := nTmp * nRaw;
IF b THEN
    nTmp := 0;
END_IF
F_L023ok := nTmp;
p := ADR(nAlias);
nAlias := 1;
nAlias := 2;
nTmp := ABS(nRaw);
nTmp := 5;
F_L023ok := nTmp + p^;
END_FUNCTION

FUNCTION_BLOCK FB_L023ok
VAR_INPUT
    nRaw : INT;
END_VAR
VAR
    nStat : INT;
END_VAR
nStat := nRaw;
nStat := nRaw + 1;
END_FUNCTION_BLOCK
