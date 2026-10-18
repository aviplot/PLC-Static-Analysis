FUNCTION_BLOCK FB_Div
VAR_INPUT
    a : DINT;
    r : REAL;
END_VAR
VAR_OUTPUT
    q : DINT;
    rq : REAL;
END_VAR
VAR CONSTANT
    cZero : DINT := 0;
    cTwo : DINT := 2;
END_VAR
q := a / 0; // PLCA-Q004
q := a MOD cZero; // PLCA-Q004
rq := r / 0.0; // PLCA-Q004
q := DIV(a, cTwo - 2); // PLCA-Q004
END_FUNCTION_BLOCK
