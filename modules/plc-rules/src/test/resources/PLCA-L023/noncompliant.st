FUNCTION F_L023 : INT
VAR_INPUT
    nRaw : INT;
    b : BOOL;
END_VAR
VAR
    nTmp : INT;
    nOther : INT;
END_VAR
nTmp := nRaw * 2; // PLCA-L023
nTmp := nRaw * 3;
nOther := 1; // PLCA-L023
IF b THEN
    nTmp := 0;
END_IF
nOther := 2;
F_L023 := nTmp + nOther;
END_FUNCTION

FUNCTION_BLOCK FB_L023
VAR_INPUT
    nRaw : INT;
END_VAR
VAR_OUTPUT
    nOut : INT;
END_VAR
VAR_TEMP
    nTmp : INT;
END_VAR
nTmp := nRaw; // PLCA-L023
nTmp := nRaw + 1;
nOut := nTmp;
END_FUNCTION_BLOCK
