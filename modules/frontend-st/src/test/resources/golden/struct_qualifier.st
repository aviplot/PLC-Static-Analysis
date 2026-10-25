// A qualifier after STRUCT applies to the whole structure (docs/grammar/codesys.md section 7, M12)
TYPE ST_Parameter :
STRUCT PERSISTENT
    rSetpoint : REAL := 1.5;
    nMode : INT;
END_STRUCT
END_TYPE

TYPE ST_AxisRetain :
STRUCT RETAIN
    lrPosition : LREAL;
END_STRUCT
END_TYPE

TYPE ST_Steps :
STRUCT CONSTANT
    nStart : INT := 10;
    nStop : INT := 20;
END_STRUCT
END_TYPE
