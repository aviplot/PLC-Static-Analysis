FUNCTION_BLOCK FB_Ok
VAR_INPUT
    diPulses : DINT;
    rSpeed : REAL;
    nIn : INT;
END_VAR
VAR
    nPulses : INT;
    diWide : DINT;
    rOut : REAL;
    lrOut : LREAL;
    nUns : UINT;
    nSmall : SINT;
    bSel : BOOL;
END_VAR
VAR CONSTANT
    cLimit : DINT := 100;
END_VAR
nPulses := DINT_TO_INT(diPulses);
nPulses := nIn + 1;
nPulses := nIn * 2 + 7;
diWide := nIn;
rOut := rSpeed * 0.5;
rOut := nIn * 0.1;
lrOut := rSpeed;
nUns := nIn;
nSmall := 100;
rOut := nIn;
rOut := MAX(0.0, rSpeed);
nPulses := SEL(bSel, 102, 11);
nPulses := cLimit;
nUns := SIZEOF(rOut);
END_FUNCTION_BLOCK
