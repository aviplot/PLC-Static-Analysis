FUNCTION F_Safe : INT
VAR_INPUT
    pA : POINTER TO INT;
    pB : POINTER TO INT;
    pC : POINTER TO INT;
END_VAR
VAR
    n : INT;
    pLocal : POINTER TO INT;
END_VAR
IF pA <> 0 THEN
    F_Safe := pA^;
END_IF
IF pB = 0 THEN
    RETURN;
END_IF
F_Safe := F_Safe + pB^;
IF 0 <> pC AND_THEN pC^ > 0 THEN
    F_Safe := 1;
END_IF
pLocal := ADR(n);
F_Safe := F_Safe + pLocal^;
END_FUNCTION
