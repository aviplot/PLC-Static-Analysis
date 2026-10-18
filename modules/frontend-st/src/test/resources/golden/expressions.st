FUNCTION F_Expr : LREAL
VAR_INPUT
    p : POINTER TO ST_Point;
END_VAR
VAR
    w : WORD;
    s : STRING;
    ws : WSTRING;
    b : BOOL;
    t : TIME;
END_VAR

F_Expr := -2 ** 2 + 3 * 4 MOD 5 - (1 + 2) / 3;
b := NOT b AND w.3 OR_ELSE p^.x > 1.5E-3 AND_THEN THIS^.bOk XOR SUPER^.bOk;
b := 1 < 2 = TRUE <> FALSE;
w := 16#FF_FF + 2#1010 + 8#17 + 10#99 + WORD#16#0F + INT#-5;
s := 'it$'s $N done';
ws := "wide $"quoted$"";
t := T#1h2m3s4ms + LTIME#-5us;
F_Expr := TO_LREAL(D#2024-01-31 - DATE#2024-01-01) + TOD#12:30:15.5 + DT#2024-01-31-12:00:00;
w := SIZEOF(POINTER TO ST_Point) + SIZEOF(STRING) + .gGlobal + AND(b, b);
F_Expr := arr[i, j + 1].field;
END_FUNCTION
