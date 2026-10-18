FUNCTION_BLOCK FB_InOk
VAR_INPUT
    bIn : BOOL;
    pIn : POINTER TO INT;
    refIn : REFERENCE TO INT;
END_VAR
VAR_IN_OUT
    nInOut : INT;
END_VAR
VAR
    bCopy : BOOL;
END_VAR
bCopy := bIn;
pIn^ := 1;
refIn := 2;
nInOut := 3;
END_FUNCTION_BLOCK

PROGRAM P_Caller
VAR
    fb : FB_InOk;
    n : INT;
END_VAR
fb.bIn := TRUE;
fb(bIn := FALSE, nInOut := n);
END_PROGRAM
