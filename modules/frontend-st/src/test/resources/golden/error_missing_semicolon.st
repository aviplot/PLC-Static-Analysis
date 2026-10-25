// A ';' left out anywhere else stays a syntax error (docs/grammar/codesys.md, M12)
TYPE ST_Row :
STRUCT
    a : INT;
    b : BOOL
END_STRUCT
END_TYPE

PROGRAM P
VAR
    a : INT
    b : INT;
END_VAR
a := 1
b := 2;
IF a > 0 THEN
    b := 3
END_IF
CASE a OF
    1: b := 4
    2: b := 5;
END_CASE
END_PROGRAM
