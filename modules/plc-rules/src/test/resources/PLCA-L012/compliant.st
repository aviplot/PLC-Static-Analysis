PROGRAM P_L012ok
VAR
    nInt : INT;
    nInt2 : INT;
    nByte : BYTE;
    nDint : DINT;
    nRange : INT(0..10);
    sName : STRING(4);
    q : BOOL;
END_VAR
q := nInt < 0;
q := nByte >= 255;
q := nInt + nInt2 > 32767;
q := nRange > 9;
q := sName = 'ABCD';
q := nDint > 2147483646;
END_PROGRAM
