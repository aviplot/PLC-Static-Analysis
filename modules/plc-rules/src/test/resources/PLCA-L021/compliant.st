PROGRAM P_L021ok
VAR
    n : INT;
    m : INT;
    r : REAL;
    b : BOOL;
    c : BOOL;
    q : BOOL;
END_VAR
IF n > 10 THEN
    // handled by the alarm task
END_IF
CASE n OF
    1: q := TRUE;
    2: (* idle: wait for start *)
ELSE
    (* unknown step: ignored on purpose *)
END_CASE
IF n > 10 THEN
ELSE
    q := TRUE;
END_IF
END_PROGRAM
