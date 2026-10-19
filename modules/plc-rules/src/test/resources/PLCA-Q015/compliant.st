FUNCTION_BLOCK FB_Pump
VAR_INPUT
    bStart : BOOL;
    rSpeed : REAL;
    xEnable : BOOL;
    sName : STRING;
    tDelay : TIME;
    aValues : ARRAY[0..3] OF INT;
    pData : POINTER TO INT;
    eMode : E_Mode;
END_VAR
END_FUNCTION_BLOCK

TYPE E_Mode : (Off, On); END_TYPE

FUNCTION F_Add : INT
VAR_INPUT
    nA, nB : INT;
END_VAR
F_Add := nA + nB;
END_FUNCTION
