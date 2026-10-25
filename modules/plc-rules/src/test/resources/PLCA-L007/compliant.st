PROGRAM P_L007ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
VAR CONSTANT
    cOn : BOOL := TRUE;
END_VAR
q := r = r;
q := n > 5 OR m <= 5;
q := cOn OR NOT cOn;
q := b OR NOT c;
END_PROGRAM
