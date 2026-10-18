FUNCTION F_Scale : REAL
VAR_INPUT
    rIn : REAL;
    nDepth : INT;
END_VAR
IF nDepth > 0 THEN
    F_Scale := F_Helper(rIn, nDepth - 1);
ELSE
    F_Scale := rIn * 2.5;
END_IF
END_FUNCTION

FUNCTION F_Helper : REAL
VAR_INPUT
    rIn : REAL;
    nDepth : INT;
END_VAR
F_Helper := F_Scale(rIn := rIn, nDepth := nDepth);
END_FUNCTION
