// Array initial values without brackets, CODESYS V2 style (docs/grammar/codesys.md section 7, M12)
PROGRAM P
VAR
    buf : ARRAY[0..3] OF BYTE := 110, 60, 3, 219; (* crypt key *)
    arr2 : ARRAY[1..2, 3..4] OF INT := 1, 3(7);
    arr3 : ARRAY[1..8] OF INT := 2(0), 4(4), 2, 3;
    one : ARRAY[1..3] OF INT := 3(5);
    n : INT := 5;
END_VAR
n := n + 1;
END_PROGRAM
