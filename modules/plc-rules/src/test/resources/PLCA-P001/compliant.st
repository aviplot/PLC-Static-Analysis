VAR_GLOBAL
    gMode : INT;
    gOk : BOOL;
END_VAR

FUNCTION_BLOCK FB_Ok
VAR_INPUT
    bA : BOOL;
    bB : BOOL;
END_VAR
VAR_OUTPUT
    bOut : BOOL;
    nOut : INT;
END_VAR
VAR
    n : INT;
END_VAR
// default, then override in an IF
bOut := FALSE;
IF bA THEN
    bOut := TRUE;
END_IF
// latches
bOut S= bA;
bOut R= bB;
// sequential use: read in between
nOut := 1;
n := nOut * 2;
nOut := n;
// locals are not coils by default
n := 0;
n := 5;
// mutually exclusive branches
IF bA THEN
    gOk := TRUE;
ELSE
    gOk := FALSE;
END_IF
END_FUNCTION_BLOCK

PROGRAM P_Ok
VAR
    fb : FB_Ok;
END_VAR
gMode := 1;
fb(bA := gMode > 0);
gMode := 2;
END_PROGRAM

VAR_GLOBAL
    gnCycles : DINT;
END_VAR

PROGRAM P_Count
gnCycles := gnCycles + 1;
END_PROGRAM

PROGRAM P_ResetCount
gnCycles := 0;
END_PROGRAM

FUNCTION F_Result : INT
VAR_INPUT
    n : INT;
END_VAR
F_Result := 0;
F_Result := n * 2;
END_FUNCTION

VAR_GLOBAL
    gLight : BOOL;
END_VAR

FUNCTION_BLOCK FB_Parser
VAR_INPUT
    bValid : BOOL;
END_VAR
VAR_OUTPUT
    bDone : BOOL;
    bOk : BOOL;
END_VAR
// clear outputs, early exit, then set them
bDone := FALSE;
bOk := FALSE;
IF NOT bValid THEN
    bDone := TRUE;
    RETURN;
END_IF
bOk := TRUE;
bDone := TRUE;

METHOD On
gLight := TRUE;
END_METHOD

METHOD Off
gLight := FALSE;
END_METHOD
END_FUNCTION_BLOCK
