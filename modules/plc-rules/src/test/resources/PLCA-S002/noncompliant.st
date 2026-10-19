VAR_GLOBAL
    GVL_HMI_rSpeed : REAL;
    GVL_HMI_bStart : BOOL;
    GVL_HMI_nIndex : INT;
    {attribute 'OPC.UA.DA' := '1'}
    gnRemoteValue : INT;
END_VAR

PROGRAM P_Out
VAR
    qrSpeed AT %QD4 : REAL;
    qxPump AT %QX0.0 : BOOL;
    qxValve AT %QX0.1 : BOOL;
    qnValue AT %QW8 : INT;
    arTable : ARRAY[0..9] OF INT;
    n : INT;
END_VAR
qrSpeed := GVL_HMI_rSpeed * 10.0; // PLCA-S002
qxPump := GVL_HMI_bStart; // PLCA-S002
IF GVL_HMI_bStart THEN
    qxValve := TRUE; // PLCA-S002
END_IF
qnValue := gnRemoteValue; // PLCA-S002
n := arTable[GVL_HMI_nIndex]; // PLCA-S002
END_PROGRAM
