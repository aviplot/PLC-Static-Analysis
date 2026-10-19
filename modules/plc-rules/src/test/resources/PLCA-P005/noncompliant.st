FUNCTION F_Read : INT
VAR_INPUT
    pValue : POINTER TO INT;
    pOther : POINTER TO INT;
END_VAR
F_Read := pValue^; // PLCA-P005
F_Read := F_Read + pValue^;
IF F_Read > 0 THEN
    F_Read := pOther^; // PLCA-P005
END_IF
END_FUNCTION
