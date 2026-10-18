TYPE E_State : (Idle, Run, Stop); END_TYPE

PROGRAM P_Case
VAR
    n : INT;
    m : INT;
    eState : E_State;
END_VAR
CASE n OF // PLCA-Q002
    1: m := 1;
END_CASE
CASE eState OF // PLCA-Q002
    Idle: m := 0;
    Run: m := 1;
END_CASE
IF m > 0 THEN
    CASE m OF 1..5: n := 0; END_CASE // PLCA-Q002
END_IF
END_PROGRAM
