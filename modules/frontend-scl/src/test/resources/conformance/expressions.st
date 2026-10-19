// precedence and literals
r := -a ** 2 * b / c MOD d + e - f;
q := NOT a AND b & c XOR d OR e = f AND g <> h;
q := (a < b) OR (a > b) OR (a <= b) OR (a >= b);
i := 16#FF_FF + 2#1010 + 8#17 + 1_000 + 10#99;
r := 1.5 + 1.5E-3 + 1E5 + REAL#-1.5 + LREAL#2.0;
w := WORD#16#FF + INT#5 + DINT#-1;
b := TRUE OR FALSE OR BOOL#TRUE;
s := 'it$'s $N$R$T $$ $41';
t := T#1h2m3s4ms + TIME#5s + LT#1us + T#-5s;
d := D#2024-01-31;
tod := TOD#12:00:00.5;
dt := DT#2024-01-31-12:00:00;
e := E_Color#Red;
x := %IX0.1 AND %QW10 > 0 OR %MD100 = 0;
x := f(g(h(1)))[2].m;
