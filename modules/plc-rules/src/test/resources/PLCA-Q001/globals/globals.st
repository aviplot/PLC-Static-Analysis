VAR_GLOBAL
    gUsed : INT;
    gNotReferenced : INT; // PLCA-Q001
    gSpareWord : WORD;
    gInit : INT := cStart;
END_VAR
VAR_GLOBAL CONSTANT
    cStart : INT := 3;
END_VAR

PROGRAM P
gUsed := gInit;
END_PROGRAM
