TYPE E_Color : (Red, Green); END_TYPE

PROGRAM P_L003ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
    eColor : E_Color;
END_VAR
IF n < 10 THEN
    q := TRUE;
ELSIF n > 10 THEN
    q := FALSE;
ELSE
    b := TRUE;
END_IF
CASE eColor OF
    E_Color.Red: q := TRUE;
    E_Color.Green: q := FALSE;
ELSE
    b := TRUE;
END_CASE
IF r < 1.0 THEN
    q := TRUE;
ELSIF r > 1.0 THEN
    q := FALSE;
ELSE
    b := TRUE;
END_IF
END_PROGRAM
