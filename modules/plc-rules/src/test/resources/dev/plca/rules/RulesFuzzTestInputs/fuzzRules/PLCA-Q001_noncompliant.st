FUNCTION_BLOCK FB_Unused
VAR_INPUT
    bIn : BOOL;
END_VAR
VAR
    nNeverUsed : INT; // PLCA-Q001
    bUsed : BOOL;
    rTemp, rOther : REAL; // PLCA-Q001 PLCA-Q001
END_VAR
VAR_TEMP
    nScratch : DINT; // PLCA-Q001
END_VAR
bUsed := bIn;
END_FUNCTION_BLOCK

PROGRAM P_Main
VAR
    fbUnused : FB_Unused;
    fbNotCalled : FB_Unused; // PLCA-Q001
    n : INT;
END_VAR
fbUnused(bIn := n > 0);
END_PROGRAM

FUNCTION F_Unused : INT
VAR_INPUT
    nIn : INT;
END_VAR
VAR
    nLocal : INT; // PLCA-Q001
END_VAR
F_Unused := nIn;
END_FUNCTION
