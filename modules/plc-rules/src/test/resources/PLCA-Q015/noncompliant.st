FUNCTION_BLOCK Motor // PLCA-Q015
VAR_INPUT
    Start : BOOL; // PLCA-Q015
    speed : REAL; // PLCA-Q015
    nCount : INT;
    bigNumber : DINT; // PLCA-Q015
END_VAR
VAR
    timer : TON; // PLCA-Q015
    fbDelay : TON;
    stData : MotorData;
END_VAR
END_FUNCTION_BLOCK

TYPE MotorData : // PLCA-Q015
STRUCT
    bOk : BOOL;
END_STRUCT
END_TYPE

VAR_GLOBAL
    g_bReady : BOOL;
    gReady : BOOL; // PLCA-Q015
END_VAR
