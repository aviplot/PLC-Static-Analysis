(* Function block with inheritance, all variable sections, methods, a property and an action. *)
{attribute 'reflection'}
FUNCTION_BLOCK PUBLIC FB_Motor EXTENDS FB_Base IMPLEMENTS I_Motor, I_Diag
VAR_INPUT
    bStart : BOOL;
    rSetpoint : REAL := 1.5;
END_VAR
VAR_OUTPUT
    bRunning AT %QX0.1 : BOOL;
END_VAR
VAR_IN_OUT
    stConfig : ST_Config;
END_VAR
VAR RETAIN PERSISTENT
    nStarts : UDINT;
END_VAR
VAR CONSTANT
    MAX_SPEED : INT := 1500;
END_VAR
VAR
    {attribute 'hide'}
    fbTimer : TON := (PT := T#2S);
    aBuf : ARRAY[0..9] OF INT := [1, 2, 8(0)];
    pData : POINTER TO BYTE;
    refCfg : REFERENCE TO ST_Config;
    fbInit : FB_Logger(10, sName := 'motor');
    a, b : INT;
END_VAR

fbTimer(IN := bStart, Q => bRunning);
IF bRunning THEN
    nStarts := nStarts + 1;
END_IF

METHOD PUBLIC Reset : BOOL
VAR_INPUT
    bForce : BOOL;
END_VAR
nStarts := 0;
Reset := TRUE;
END_METHOD

PROPERTY PUBLIC Speed : INT
GET
    Speed := MAX_SPEED;
END_GET
SET
    ;
END_SET
END_PROPERTY

ACTION Stop:
    bRunning := FALSE;
END_ACTION
END_FUNCTION_BLOCK
