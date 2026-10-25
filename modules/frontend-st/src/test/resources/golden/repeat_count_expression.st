// Array repetition counts that are constant expressions (docs/grammar/codesys.md section 7, M12)
VAR_GLOBAL CONSTANT
    N : INT := 4;
END_VAR

PROGRAM P
VAR
    aText : ARRAY[0..N] OF STRING(255) := [N + 1(',')];
    aFlags : ARRAY[0..N - 1] OF ST_Flag := [(N - 1)((bOn := TRUE, sName := '')), (bOn := FALSE, sName := 'x')];
    aMix : ARRAY[1..6] OF INT := [2(0), GVL.M(1), 3];
    aCalls : ARRAY[1..2] OF INT := [F(1), F(2)];
END_VAR
aText[0] := '';
END_PROGRAM
