// Pump with a run command.
FUNCTION_BLOCK FB_Pump
VAR_INPUT
    bRun : BOOL; // run command from MAIN
END_VAR
VAR_OUTPUT
    bRunning : BOOL;
END_VAR
bRunning := bRun;
END_FUNCTION_BLOCK
