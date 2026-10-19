FUNCTION_BLOCK FB_Tank
VAR_INPUT
    rLevel : REAL;
    nMode : INT;
END_VAR
VAR
    bHigh : BOOL;
    nCount : INT;
    arBuf : ARRAY[0..9] OF INT;
    i : INT;
END_VAR
bHigh := rLevel > 87.5; // PLCA-Q014
nCount := nCount + 7; // PLCA-Q014
nCount := -42; // PLCA-Q014
CASE nMode OF
    3: nCount := 0;
    4..5: nCount := 1;
END_CASE
FOR i := 0 TO 9 DO
    arBuf[3] := i;
END_FOR
nCount := nCount * 10;
nCount := nCount + 16#FF + INT#3;
bHigh := rLevel > 1.0;
END_FUNCTION_BLOCK
