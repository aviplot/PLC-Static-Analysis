PROGRAM P_L021
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
(* PLCA-L021 *) IF n > 10 THEN
END_IF
IF n > 10 THEN
    q := TRUE;
ELSIF (* PLCA-L021 *) n > 5 THEN
ELSE
    q := FALSE;
END_IF
IF n > 10 THEN
    q := TRUE;
(* PLCA-L021 *) ELSE
END_IF
CASE n OF
    1: q := TRUE;
    (* PLCA-L021 *) 2:
END_CASE
CASE n OF
    1: q := TRUE;
(* PLCA-L021 *) ELSE
END_CASE
END_PROGRAM
