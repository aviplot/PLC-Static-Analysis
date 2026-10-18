FUNCTION F_Twice : INT
VAR_INPUT
    n : INT;
END_VAR
F_Twice := F_Plus(n, n);
END_FUNCTION

FUNCTION F_Plus : INT
VAR_INPUT
    a : INT;
    b : INT;
END_VAR
F_Plus := a + b;
END_FUNCTION
