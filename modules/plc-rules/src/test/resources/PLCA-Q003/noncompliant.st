FUNCTION_BLOCK FB_Float
VAR_INPUT
    r : REAL;
    lr : LREAL;
    n : INT;
END_VAR
VAR_OUTPUT
    b : BOOL;
END_VAR
b := r = 1.5; // PLCA-Q003
IF lr <> r THEN // PLCA-Q003
    b := FALSE;
END_IF
b := n = 0.5; // PLCA-Q003
b := r * 2.0 = INT_TO_REAL(n); // PLCA-Q003
END_FUNCTION_BLOCK
