// A ';' left out before END_VAR or after the last statement of a body: a warning (docs/grammar/codesys.md, M12)
FUNCTION_BLOCK FB_Collection
VAR_INPUT
    bInitRetains : BOOL; // initialise retain variables
    T : Tc2_Utilities.T_Arg // provide the type of the collection
END_VAR
VAR_IN_OUT
    XTSUnitRef : I_XtsUnit//FB_XtsUnit;
END_VAR
VAR
    n : INT;
END_VAR
n := n + 1;
Engine.oRun := n > 2
METHOD M : BOOL
M := TRUE
END_METHOD
END_FUNCTION_BLOCK

FUNCTION F_Speed : UINT
VAR_INPUT
    iAnalog : INT;
END_VAR
F_Speed := TO_UINT(LIMIT(0, iAnalog, 24000))
