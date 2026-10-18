TYPE E_Mode : (Manual, Auto := 5, Service); END_TYPE

PROGRAM P_CaseOk
VAR
    n : INT;
    m : INT;
    eMode : E_Mode;
END_VAR
CASE n OF
    1: m := 1;
ELSE
    m := 0;
END_CASE
CASE eMode OF
    E_Mode.Manual: m := 0;
    Auto, Service: m := 1;
END_CASE
END_PROGRAM
