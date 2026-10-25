PROGRAM P_L006ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
VAR CONSTANT
    cLimit : INT := 20;
END_VAR
CASE n OF
    0: q := FALSE;
    10: q := TRUE;
    11..15: b := TRUE;
    cLimit: c := TRUE;
ELSE
    q := FALSE;
END_CASE
END_PROGRAM
