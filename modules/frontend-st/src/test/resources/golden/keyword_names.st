(* CONTINUE as a variable name: declared, read, written, as a member; CONTINUE; stays the loop statement. *)
FUNCTION_BLOCK FB_Axis
VAR_INPUT
    Power : BOOL;
    SingleStep : BOOL;
    Continue : BOOL;
    MoveInterrupted : BOOL;
END_VAR
VAR
    i : INT;
END_VAR
IF Continue THEN
    Continue := FALSE;
END_IF
FOR i := 1 TO 10 DO
    IF i = 5 THEN
        CONTINUE;
    END_IF
END_FOR
END_FUNCTION_BLOCK

PROGRAM MAIN
VAR
    fbMy6Axis : FB_Axis;
    bEnable : BOOL;
END_VAR
IF fbMy6Axis.Power=TRUE AND fbMy6Axis.SingleStep=TRUE THEN
    IF fbMy6Axis.Continue=TRUE AND fbMy6Axis.MoveInterrupted=TRUE THEN
        fbMy6Axis.Continue:=FALSE;
    END_IF
    fbMy6Axis(Continue := bEnable);
END_IF
END_PROGRAM
