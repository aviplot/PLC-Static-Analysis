(* Instruction List POUs next to an ST one *)
FUNCTION_BLOCK FB_Distance
VAR_INPUT
    X1, X2, Y1, Y2 : REAL;
    TMax : REAL;
END_VAR
VAR_OUTPUT
    Dist : REAL;
    Ok : BOOL;
END_VAR
VAR
    Temp : REAL;
    Pulse : TON;
END_VAR
    LD   Y1
    SUB  Y2         (* Y1 - Y2 *)
    ST   Temp
    MUL  Temp
    ADD( X1         (* deferred: + (X1 - X2)^2 *)
    SUB  X2
    ST   Temp
    MUL  Temp
    )
    SQRT
    ST   Dist
    GT   TMax
    JMPC TooFar
    S    Ok
    CAL  Pulse(IN := Ok,
               PT := T#1S)
    RET
TooFar:
    LDN  Pulse.Q
    R    Ok
END_FUNCTION_BLOCK

PROGRAM Counter
VAR
    Count : INT;
    Done : BOOL;
END_VAR
LD Count;
ADD 1;
ST Count;
GE 10;
ST Done;
END_PROGRAM

FUNCTION_BLOCK FB_St
VAR
    a : INT;
END_VAR
a := a + 1;
END_FUNCTION_BLOCK
