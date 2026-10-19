FUNCTION_BLOCK FB_Count
VAR_INPUT
    diPulses : DINT;
    rSpeed : REAL;
    lrPos : LREAL;
    dwStatus : DWORD;
END_VAR
VAR
    nPulses : INT;
    nSpeed : INT;
    rPos : REAL;
    wStatus : WORD;
    nSmall : SINT;
    nUns : UINT;
END_VAR
nPulses := diPulses; // PLCA-Q011
nSpeed := rSpeed; // PLCA-Q011
rPos := lrPos; // PLCA-Q011
wStatus := dwStatus; // PLCA-Q011
nPulses := nPulses + diPulses; // PLCA-Q011
nSmall := 200; // PLCA-Q011
nUns := -1; // PLCA-Q011
F_Take(n := diPulses); // PLCA-Q011
F_Give(d => nPulses); // PLCA-Q011
END_FUNCTION_BLOCK

FUNCTION F_Take : BOOL
VAR_INPUT
    n : INT;
END_VAR
F_Take := n > 0;
END_FUNCTION

FUNCTION F_Give : BOOL
VAR_OUTPUT
    d : DINT;
END_VAR
d := 1;
END_FUNCTION
