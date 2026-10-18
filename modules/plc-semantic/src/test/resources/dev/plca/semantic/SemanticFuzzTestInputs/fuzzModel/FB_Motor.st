FUNCTION_BLOCK FB_Motor EXTENDS FB_Base
VAR_INPUT
    rSetpoint : T_Speed;
END_VAR
VAR_OUTPUT
    bRunning : BOOL;
END_VAR
VAR_IN_OUT
    stData : ST_Motor;
END_VAR
VAR
    fbDelay : Tc2_Standard.TON;
    fbPower : Tc2_MC2.MC_Power;
    eState : E_State;
    pData : POINTER TO ST_Motor;
END_VAR

fbDelay(IN := bEnable, PT := T#500MS, Q => bRunning);
CASE eState OF
    Idle: IF bEnable THEN eState := E_State.Starting; END_IF
    Starting: eState := E_State.Running;
END_CASE
stData.aLog[3] := nCalls;
stData.rSpeed := rSetpoint;
pData := ADR(stData);
fbPower(Axis := gAxis, Enable := bEnable);
Stop();

METHOD PUBLIC Reset : BOOL
bRunning := FALSE;
Reset := SUPER^.Reset();
END_METHOD

PROPERTY PUBLIC Speed : REAL
GET
    Speed := stData.rSpeed;
END_GET
END_PROPERTY

ACTION Stop:
    bRunning := FALSE;
END_ACTION
END_FUNCTION_BLOCK
