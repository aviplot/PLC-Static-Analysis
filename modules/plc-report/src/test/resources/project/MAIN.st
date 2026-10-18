PROGRAM MAIN
VAR
    qPump AT %QX0.0 : BOOL;
    rLevel : REAL;
    nStep : INT;
    sPassword : STRING := 'secret1';
    nUnused : INT;
END_VAR
qPump := rLevel = 100.0;
CASE nStep OF
    0: nStep := 1;
END_CASE
qPump := %IX0.1;
END_PROGRAM
