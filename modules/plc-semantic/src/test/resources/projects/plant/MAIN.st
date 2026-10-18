PROGRAM MAIN
VAR
    fbMotor : FB_Motor;
    aMotors : ARRAY[1..2] OF FB_Motor;
    stMotor : ST_Motor;
    rSpeed : REAL;
    n : DINT;
    i : INT;
    eMode : E_Mode;
END_VAR
fbMotor(bEnable := GVL_IO.bStart, rSetpoint := 1500.0, stData := stMotor);
rSpeed := fbMotor.Speed + F_Scale(rSpeed, 3);
n := i + 1;
FOR i := 1 TO 2 DO
    aMotors[i](stData := stMotor);
END_FOR
gnCounter := gnCounter + 1;
eMode := Auto;
IF fbMotor.Reset() THEN
    ;
END_IF
END_PROGRAM

PROGRAM FAST
gnCounter := 0;
END_PROGRAM
