TYPE E_Color : (Red, Green); END_TYPE

PROGRAM P_L006
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    eColor : E_Color;
END_VAR
CASE n OF
    0: q := FALSE;
    10: q := TRUE;
    5..15: b := TRUE; // PLCA-L006
    0, 20: c := TRUE; // PLCA-L006
ELSE
    q := FALSE;
END_CASE
CASE eColor OF
    E_Color.Red: q := TRUE;
    E_Color.Green: q := FALSE;
    E_Color.Red: b := TRUE; // PLCA-L006
END_CASE
END_PROGRAM
