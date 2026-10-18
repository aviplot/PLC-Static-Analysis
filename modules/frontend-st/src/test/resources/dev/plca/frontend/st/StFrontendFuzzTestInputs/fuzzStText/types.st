TYPE
    ST_Point EXTENDS ST_Base :
    STRUCT
        x : REAL;
        y : REAL := 0.0;
    END_STRUCT
    E_State : (Idle := 0, Running, Faulted := 10) INT := Idle;
    U_Raw :
    UNION
        dw : DWORD;
        ab : ARRAY[0..3] OF BYTE;
    END_UNION
    T_Name : STRING(80);
    T_Percent : INT(0..100);
    T_Matrix : ARRAY[1..3, 1..3] OF LREAL;
    T_Ref : REF_TO INT;
END_TYPE
