VAR_GLOBAL
    gLamp : BOOL;
    gValve : BOOL;
END_VAR

PROGRAM P_A
VAR
    qMotor AT %QX0.0 : BOOL;
    b1 : BOOL;
    b2 : BOOL;
    tmr : TON;
END_VAR
qMotor := b1;
qMotor := b2; // PLCA-P001
gLamp := b1;
tmr(IN := b1, PT := T#1S, Q => gValve);
END_PROGRAM

PROGRAM P_B
VAR
    b : BOOL;
END_VAR
gLamp := b; // PLCA-P001
gValve := NOT b; // PLCA-P001
END_PROGRAM

FUNCTION_BLOCK FB_Out
VAR_INPUT
    bIn : BOOL;
END_VAR
VAR_OUTPUT
    bOut : BOOL;
END_VAR
bOut := bIn;
bOut := NOT bIn; // PLCA-P001
END_FUNCTION_BLOCK
