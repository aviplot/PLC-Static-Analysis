// TYPE with an access modifier, empty structured initialisers (docs/grammar/codesys.md section 7, M12)
TYPE INTERNAL E_State :
(
    Idle,
    Busy
);
END_TYPE

TYPE INTERNAL ST_Row :
STRUCT
    a : INT;
    b : BOOL;
END_STRUCT
END_TYPE

PROGRAM P
VAR
    fbList : FB_List := ();
    stRow : ST_Row := ();
    fbFree : FB_Free(42) := ();
    aRows : ARRAY[1..15] OF ST_Row := [15(())];
    aMixed : ARRAY[1..3] OF ST_Row := [(a := 1), 2(())];
END_VAR
stRow.a := 1;
END_PROGRAM
