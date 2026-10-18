FUNCTION_BLOCK FB_Used
VAR
    nCount : INT;
    nForChildren : INT;
    aBuf : ARRAY[0..cMax] OF INT;
    nSpare1 : INT;
    bReserve_2 : BOOL;
    {attribute 'OPC.UA.DA' := '1'}
    rExposed : REAL;
    iSensor AT %I* : BOOL;
    pTarget : POINTER TO INT;
    nAddr : INT;
    nReadFromOutside : INT;
END_VAR
VAR CONSTANT
    cMax : INT := 9;
END_VAR
pTarget := ADR(nAddr);
aBuf[0] := 1;

METHOD Inc
nCount := nCount + 1;
END_METHOD
END_FUNCTION_BLOCK

FUNCTION_BLOCK FB_Child EXTENDS FB_Used
nForChildren := 0;
END_FUNCTION_BLOCK

FUNCTION_BLOCK ABSTRACT FB_Abstract_Base
VAR
    nForImplementations : INT;
END_VAR
END_FUNCTION_BLOCK

PROGRAM P_User
VAR
    fb : FB_Used;
    n : INT;
END_VAR
fb.Inc();
n := fb.nReadFromOutside;
END_PROGRAM
