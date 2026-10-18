FUNCTION_BLOCK FB_FloatOk
VAR_INPUT
    r : REAL;
    n : INT;
END_VAR
VAR_OUTPUT
    b : BOOL;
END_VAR
VAR CONSTANT
    cA : REAL := 1.0;
    cB : REAL := 2.0;
END_VAR
b := ABS(r - 1.5) < 0.001;
b := n = 3;
b := r > 1.0;
b := cA = cB;
END_FUNCTION_BLOCK
