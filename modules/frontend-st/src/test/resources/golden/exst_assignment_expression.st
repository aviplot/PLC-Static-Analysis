// CODESYS ExST: an assignment used as an expression (docs/grammar/codesys.md section 7, M12)
FUNCTION_BLOCK FB_ExSt
VAR
    b, bInit, bOk : BOOL;
    i, n : INT;
    s : STRING;
    p : POINTER TO INT;
    st : ST_Item;
    arr : ARRAY[0..9] OF INT;
END_VAR

// whole condition: the value is the rest of the expression
IF b := (i = 1) THEN
    i := i + 1;
ELSIF st.bDone := i > 2 OR b THEN
    i := 0;
END_IF
WHILE p^ := p^ - 1 DO
    ;
END_WHILE
REPEAT
    i := i + 1;
UNTIL bOk := i >= 10
END_REPEAT

// in parentheses, anywhere an expression is
IF (n := FIND(s, 'x')) > 0 AND_THEN (arr[n] := n * 2) <> 0 THEN
    s := '';
END_IF
CASE (n := n + 1) OF
    1: i := 1;
END_CASE
b := NOT (bOk := fbChild.Execute());
RETURN(bInit := i <= 0);

// statement level stays a chained assignment
i := n := 0;
END_FUNCTION_BLOCK
