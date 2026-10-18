PROGRAM MAIN
VAR
    i, n : INT;
    eState : E_State;
    bA, bB, bC : BOOL;
    r : REFERENCE TO INT;
    itf : I_Motor;
END_VAR

CASE eState OF
    E_State.Idle, E_State#Running:
        n := 1;
    1..5, 7:
        n := 2;
ELSE
    n := 0;
END_CASE;

FOR i := 0 TO 10 BY 2 DO
    IF i = 4 THEN CONTINUE; END_IF
    IF i > 8 THEN EXIT; END_IF
END_FOR

WHILE n < 10 DO n := n + 1; END_WHILE
REPEAT n := n - 1; UNTIL n <= 0 END_REPEAT

bA S= bB;
bA R= bC;
r REF= n;
itf ?= fbAny;
bA := bB := bC := FALSE;

JMP lbl;
lbl:
__TRY
    n := 1 / n;
__CATCH(exc)
    n := 0;
__FINALLY
    ;
__ENDTRY

fbT(IN := bA, PT := T#1S, Q => bB, NOT ET => );
RETURN;
END_PROGRAM
