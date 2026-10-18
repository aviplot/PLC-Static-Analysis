FUNCTION_BLOCK FB_ConnOk
VAR_INPUT
    sPassword : STRING;
    sExpected : STRING;
END_VAR
VAR
    sPwd : STRING;
    sHost : STRING;
    sMask : STRING;
    sAms : STRING;
    sMsg : STRING;
    sVersion : STRING;
    nPasswordLength : INT;
    bOk : BOOL;
END_VAR
sPwd := '';
sHost := '127.0.0.1';
sMask := '255.255.255.0';
sAms := '5.12.34.56.1.1';
sMsg := 'Password wrong';
sVersion := '1.2.3';
sHost := '0.0.0.0';
nPasswordLength := 8;
bOk := sPassword = sExpected;
END_FUNCTION_BLOCK
