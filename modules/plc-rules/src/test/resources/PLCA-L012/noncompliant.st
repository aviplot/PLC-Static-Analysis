PROGRAM P_L012
VAR
    nUsint : USINT;
    nByte : BYTE;
    nUint : UINT;
    nSint : SINT;
    nRange : INT(0..10);
    sName : STRING(4);
    b : BOOL;
    q : BOOL;
END_VAR
IF nUsint < 0 THEN // PLCA-L012
    q := TRUE;
END_IF
q := nByte > 255; // PLCA-L012
q := nUint >= 0; // PLCA-L012
q := nRange > 10; // PLCA-L012
q := sName = 'TOO_LONG'; // PLCA-L012
q := sName <> 'ALSO_TOO_LONG'; // PLCA-L012
q := nSint = 200 OR b; // PLCA-L012
END_PROGRAM
