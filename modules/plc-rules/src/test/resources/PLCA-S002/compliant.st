VAR_GLOBAL
    GVL_HMI_rSetpoint : REAL;
    GVL_HMI_bRun : BOOL;
    GVL_HMI_nSlot : INT;
    gbFault : BOOL;
    gnInternal : INT;
END_VAR

PROGRAM P_Safe
VAR
    qrOut AT %QD8 : REAL;
    qxRun AT %QX1.0 : BOOL;
    qnOut AT %QW12 : INT;
    arTable : ARRAY[0..9] OF INT;
    n : INT;
END_VAR
qrOut := LIMIT(0.0, GVL_HMI_rSetpoint, 100.0);
qxRun := GVL_HMI_bRun AND NOT gbFault;
qnOut := gnInternal;
IF GVL_HMI_nSlot >= 0 AND GVL_HMI_nSlot <= 9 THEN
    n := arTable[GVL_HMI_nSlot];
END_IF
gnInternal := n;
END_PROGRAM
