TYPE T_Point : STRUCT
    x : REAL;
    y : REAL := 1.5;
    tags : ARRAY[1..2, 0..3] OF INT;
    name : STRING[10] := 'p';
    wide : WSTRING(20);
    ref : REF_TO INT;
    limited : INT(0..100);
    nested : STRUCT
        a : BOOL;
    END_STRUCT;
    list : ARRAY[0..2] OF INT := [1, 2, 3];
    rep : ARRAY[0..9] OF INT := [10(0)];
    init : T_Other := (a := 1, b := 2);
END_STRUCT;
END_TYPE
TYPE E_Mode : (Off, Auto := 5, Manual) INT; END_TYPE
TYPE T_Alias : INT; END_TYPE
