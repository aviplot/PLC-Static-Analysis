FUNCTION F_Sum : INT
VAR_INPUT
    a : ARRAY[0..3] OF INT;
    bMode : BOOL;
END_VAR
VAR
    i : INT;
    nSum : INT;
    nLast : INT;
    nPick : INT;
    nOnce : INT;
END_VAR
FOR i := 0 TO 3 DO
    nSum := nSum + a[i]; // accumulator on the zero default: not reported
END_FOR
IF bMode THEN
    nPick := 1;
END_IF
F_Sum := nPick; // PLCA-Q010
F_Sum := F_Sum + nLast; // PLCA-Q010
WHILE i > 0 DO
    nOnce := 1;
    i := i - 1;
END_WHILE
F_Sum := F_Sum + nOnce; // PLCA-Q010
END_FUNCTION

FUNCTION_BLOCK FB_Temp
VAR_TEMP
    nTmp : INT;
END_VAR
VAR
    nStatic : INT;
END_VAR
nStatic := nStatic + nTmp; // PLCA-Q010
END_FUNCTION_BLOCK

FUNCTION F_Result : INT
F_Result := F_Result + 1; // self-update: not reported
END_FUNCTION
