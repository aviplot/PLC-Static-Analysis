IF a > 1 AND NOT b THEN
    c := c + 1;
ELSIF d <= 2.5 OR e THEN
    c := 0;
ELSE
    f := (g - h) * 2 / i MOD 3;
END_IF;
CASE n OF
    1, 2: x := 1;
    3..5: x := 2;
ELSE
    x := 0;
END_CASE;
FOR i := 0 TO 10 BY 2 DO
    arr[i] := arr[i + 1] ** 2;
    IF arr[i] > 100 THEN EXIT; END_IF;
END_FOR;
WHILE n > 0 DO n := n - 1; END_WHILE;
REPEAT n := n + 1; UNTIL n >= 10 END_REPEAT;
w := 16#FF AND 2#1010 XOR 8#17;
s := 'text$Nline';
y := ABS(-z) + SQRT(q);
m.a.b[1, 2].c := r.5;
