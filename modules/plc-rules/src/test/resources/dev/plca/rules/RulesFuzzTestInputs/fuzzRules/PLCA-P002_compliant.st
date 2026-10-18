PROGRAM P_IoOk
VAR
    iButton AT %IX0.0 : BOOL;
    qLamp AT %QX0.1 : BOOL;
    iTemp AT %I* : INT;
END_VAR
qLamp := iButton AND iTemp > 0;
END_PROGRAM
