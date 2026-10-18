VAR_GLOBAL
    gsPlcIp : STRING := '10.0.0.15'; // PLCA-S001
    gsApiToken : STRING := 'abc123xyz'; // PLCA-S001
END_VAR

FUNCTION_BLOCK FB_Conn
VAR_INPUT
    sPassword : STRING;
END_VAR
VAR
    sPwd : STRING;
    sHost : STRING;
    bOk : BOOL;
    fbClient : FB_TcpClient;
    wsKennwort : WSTRING := "geheim"; // PLCA-S001
END_VAR
sPwd := 'hunter2'; // PLCA-S001
sHost := '192.168.1.100:502'; // PLCA-S001
bOk := sPassword = 'letmein'; // PLCA-S001
fbClient(sRemoteHost := '172.16.0.2', sPassword := 'admin'); // PLCA-S001 PLCA-S001
END_FUNCTION_BLOCK
