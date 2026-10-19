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
END_FUNCTION_BLOCK
