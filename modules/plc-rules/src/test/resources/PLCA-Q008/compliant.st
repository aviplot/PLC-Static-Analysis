PROGRAM P_Main2
VAR
    nCount : INT;
END_VAR
nCount := F_Used2(nCount);
END_PROGRAM

FUNCTION F_Used2 : INT
VAR_INPUT
    n : INT;
END_VAR
F_Used2 := n + 1;
END_FUNCTION
