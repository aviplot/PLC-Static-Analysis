// CODESYS ExST: the left side of an assignment in an expression must be a variable (M12)
FUNCTION F_Bad : BOOL
VAR
    a, b : BOOL;
END_VAR
IF (a AND b := TRUE) THEN
    F_Bad := TRUE;
END_IF
END_FUNCTION
