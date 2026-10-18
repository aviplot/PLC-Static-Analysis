TYPE E_Light : (Red, Green); END_TYPE

PROGRAM P_Strict
VAR
    eLight : E_Light;
    b : BOOL;
END_VAR
CASE eLight OF // PLCA-Q002
    Red: b := FALSE;
    Green: b := TRUE;
END_CASE
END_PROGRAM
