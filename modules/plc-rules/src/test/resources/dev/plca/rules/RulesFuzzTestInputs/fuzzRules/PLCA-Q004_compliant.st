FUNCTION_BLOCK FB_DivOk
VAR_INPUT
    a : DINT;
    r : REAL;
END_VAR
VAR_OUTPUT
    q : DINT;
    rq : REAL;
END_VAR
VAR CONSTANT
    cTwo : DINT := 2;
END_VAR
q := a / cTwo;
IF a <> 0 THEN
    q := 100 / a;
END_IF
rq := r / 2.0;
q := DIV(a, cTwo);
END_FUNCTION_BLOCK
