(* core ST statements: both frontends must build the same AST *)
x := 1;
a := b := 0;
fb(in1 := x, in2 := , NOT q => y, out => z);
f(x, 2, y);
IF x > 0 THEN
    y := 1;
ELSIF x < 0 THEN
    y := -1;
ELSE
    y := 0;
END_IF;
CASE state OF
    0: y := 0;
    1, 2: y := 1;
    3..5, 7: y := 2;
        z := 3;
ELSE
    y := 9;
END_CASE;
FOR i := 0 TO 10 BY 2 DO
    IF i = 4 THEN CONTINUE; END_IF;
    IF i > 8 THEN EXIT; END_IF;
    arr[i, i + 1] := i;
END_FOR;
WHILE x < 10 DO x := x + 1; END_WHILE;
REPEAT x := x - 1; UNTIL x <= 0 END_REPEAT;
ref ?= other;
p^.member.sub[1]^ := w.3;
x;
RETURN;
