// Enumerations with a comma after the last value (docs/grammar/codesys.md section 7, M12)
TYPE E_Handshake :
(
    Load := 1,
    CameraCheck := 2,
);
END_TYPE

TYPE E_SystemState :
(
    IDLE := 0,
    RUNNING := 1, // comment after the last comma
) WORD;
END_TYPE

TYPE E_Plain : (Red, Green, Blue,) := Green;
END_TYPE
