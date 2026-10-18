package dev.plca.devtools.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic generator of representative CODESYS ST: function blocks with typical variable sections, CASE state
 * machines, FOR loops, timer/FB calls, method bodies and a GVL, plus a MAIN program instantiating them. Lets the
 * benchmarks run without the (local-only) corpus.
 */
public final class SyntheticProject {

    private SyntheticProject() {}

    /** {@code path → text}; about 120 lines per FB. */
    public static Map<String, String> generate(int functionBlocks, long seed) {
        Random rnd = new Random(seed);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("GVL.st", """
                VAR_GLOBAL
                    gnCycle : UDINT;
                    gbEnable : BOOL;
                    garValues : ARRAY[0..99] OF LREAL;
                END_VAR
                """);
        files.put("E_State.st", """
                TYPE E_State : (Idle := 0, Starting, Running, Stopping, Faulted); END_TYPE
                TYPE ST_Data :
                STRUCT
                    nId : UDINT;
                    rValue : LREAL;
                    sName : STRING(40);
                    aHistory : ARRAY[0..15] OF INT;
                END_STRUCT
                END_TYPE
                """);
        StringBuilder main = new StringBuilder("PROGRAM MAIN\nVAR\n");
        for (int i = 0; i < functionBlocks; i++) {
            main.append("    fb").append(i).append(" : FB_Unit").append(i).append(";\n");
            files.put("FB_Unit" + i + ".st", functionBlock(i, rnd));
        }
        main.append("END_VAR\ngnCycle := gnCycle + 1;\n");
        for (int i = 0; i < functionBlocks; i++) {
            main.append("fb")
                    .append(i)
                    .append("(bEnable := gbEnable, rSetpoint := garValues[")
                    .append(i % 100)
                    .append("]);\n");
        }
        main.append("END_PROGRAM\n");
        files.put("MAIN.st", main.toString());
        return files;
    }

    private static String functionBlock(int i, Random rnd) {
        int k = 1 + rnd.nextInt(5);
        return """
                {attribute 'reflection'}
                FUNCTION_BLOCK FB_Unit%1$d
                VAR_INPUT
                    bEnable : BOOL;
                    rSetpoint : LREAL;
                    nMode : INT := %2$d;
                END_VAR
                VAR_OUTPUT
                    bBusy : BOOL;
                    bError : BOOL;
                    nErrorId : UDINT;
                END_VAR
                VAR
                    eState : E_State;
                    fbTimer : TON;
                    fbTrig : R_TRIG;
                    stData : ST_Data;
                    rFiltered : LREAL;
                    i, j : INT;
                    aBuffer : ARRAY[0..31] OF LREAL;
                END_VAR
                VAR CONSTANT
                    FILTER : LREAL := 0.%2$d5;
                END_VAR

                fbTrig(CLK := bEnable);
                fbTimer(IN := eState = E_State.Starting, PT := T#%3$dMS);
                CASE eState OF
                    E_State.Idle:
                        IF fbTrig.Q AND NOT bError THEN
                            eState := E_State.Starting;
                        END_IF
                    E_State.Starting:
                        IF fbTimer.Q THEN
                            eState := E_State.Running;
                        ELSIF NOT bEnable THEN
                            eState := E_State.Stopping;
                        END_IF
                    E_State.Running:
                        rFiltered := rFiltered + FILTER * (rSetpoint - rFiltered);
                        FOR i := 0 TO 31 DO
                            aBuffer[i] := aBuffer[i] * 0.5 + rFiltered * (i MOD %4$d + 1);
                            IF aBuffer[i] > 1.0E6 OR aBuffer[i] < -1.0E6 THEN
                                bError := TRUE;
                                nErrorId := 16#8000_0000 + UDINT#%1$d;
                                EXIT;
                            END_IF
                        END_FOR
                        IF NOT bEnable THEN
                            eState := E_State.Stopping;
                        END_IF
                    E_State.Stopping:
                        rFiltered := 0;
                        eState := E_State.Idle;
                ELSE
                    eState := E_State.Faulted;
                END_CASE
                bBusy := eState <> E_State.Idle AND eState <> E_State.Faulted;
                stData.nId := %1$d;
                stData.rValue := rFiltered;
                stData.aHistory[j MOD 16] := TO_INT(rFiltered);
                j := j + 1;
                IF bError THEN
                    Reset(bForce := FALSE);
                END_IF

                METHOD PUBLIC Reset : BOOL
                VAR_INPUT
                    bForce : BOOL;
                END_VAR
                VAR
                    n : INT;
                END_VAR
                IF bForce OR eState = E_State.Faulted THEN
                    FOR n := 0 TO 31 DO
                        aBuffer[n] := 0;
                    END_FOR
                    bError := FALSE;
                    nErrorId := 0;
                    eState := E_State.Idle;
                    Reset := TRUE;
                END_IF
                END_METHOD

                PROPERTY PUBLIC Value : LREAL
                GET
                    Value := rFiltered;
                END_GET
                END_PROPERTY
                END_FUNCTION_BLOCK
                """.formatted(i, 1 + rnd.nextInt(9), 100 + rnd.nextInt(900), k);
    }
}
