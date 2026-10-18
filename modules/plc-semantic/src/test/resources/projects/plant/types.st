{attribute 'qualified_only'}
{attribute 'strict'}
TYPE E_State : (Idle, Starting := 5, Running, Faulted) INT;
END_TYPE

TYPE E_Mode : (Manual, Auto);
END_TYPE

TYPE
    ST_Base :
    STRUCT
        nId : UDINT;
    END_STRUCT
    ST_Motor EXTENDS ST_Base :
    STRUCT
        rSpeed : REAL;
        aLog : ARRAY[0..LOG_SIZE - 1] OF INT;
    END_STRUCT
    T_Speed : REAL;
END_TYPE
