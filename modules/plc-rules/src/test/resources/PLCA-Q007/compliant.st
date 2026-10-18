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

FUNCTION_BLOCK FB_Timer
METHOD Pulse : BOOL
VAR_INPUT
    bIn : BOOL;
END_VAR
Pulse := THIS^.Pulse(bIn, T#1S);
END_METHOD
METHOD Pulse : BOOL
VAR_INPUT
    bIn : BOOL;
    tLength : TIME;
END_VAR
Pulse := bIn;
END_METHOD
END_FUNCTION_BLOCK

FUNCTION Equal : BOOL
VAR_INPUT
    expected : STRING;
    actual : STRING;
END_VAR
Equal := Equal(expected := TRUE, actual := expected = actual);
END_FUNCTION
