package dev.plca.devtools.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic generator of representative TIA Portal SCL: function blocks with block headers, attributes, typical
 * variable sections, regions, CASE state machines, FOR loops, IEC timer and multi-instance calls, slices and global DB
 * access, plus a UDT, a global DB, instance DBs and an OB calling them. Lets the benchmarks run without the
 * (local-only) corpus; about the same size per block as {@link SyntheticProject}.
 */
public final class SyntheticSclProject {

    private SyntheticSclProject() {}

    /** {@code path → text}; about 110 lines per FB. */
    public static Map<String, String> generate(int functionBlocks, long seed) {
        Random rnd = new Random(seed);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("UDT_Data.udt", """
                TYPE "UDT_Data"
                VERSION : 0.1
                   STRUCT
                      Id : UDInt;
                      Value : LReal;
                      Name : String[40];
                      History : Array[0..15] of Int;
                   END_STRUCT;

                END_TYPE
                """);
        files.put("DB_Global.db", """
                DATA_BLOCK "DB_Global"
                { S7_Optimized_Access := 'TRUE' }
                VERSION : 0.1
                NON_RETAIN
                   STRUCT
                      Cycle : UDInt;
                      Enable : Bool;
                      Values : Array[0..99] of LReal;
                   END_STRUCT;

                BEGIN
                   Enable := TRUE;
                END_DATA_BLOCK
                """);
        StringBuilder main = new StringBuilder("""
                ORGANIZATION_BLOCK "Main"
                TITLE = "Main Program Sweep (Cycle)"
                { S7_Optimized_Access := 'TRUE' }
                VERSION : 0.1
                BEGIN
                	"DB_Global".Cycle += 1;
                """);
        for (int i = 0; i < functionBlocks; i++) {
            main.append("\t\"Unit")
                    .append(i)
                    .append("_DB\"(Enable := \"DB_Global\".Enable, Setpoint := \"DB_Global\".Values[")
                    .append(i % 100)
                    .append("]);\n");
            files.put(
                    "Unit" + i + "_DB.db",
                    "DATA_BLOCK \"Unit" + i + "_DB\"\n{ S7_Optimized_Access := 'TRUE' }\n"
                            + "VERSION : 0.1\nNON_RETAIN\n\"FB_Unit" + i + "\"\n\nBEGIN\nEND_DATA_BLOCK\n");
            files.put("FB_Unit" + i + ".scl", functionBlock(i, rnd));
        }
        main.append("END_ORGANIZATION_BLOCK\n");
        files.put("Main.scl", main.toString());
        return files;
    }

    private static String functionBlock(int i, Random rnd) {
        int states = 4 + rnd.nextInt(5);
        StringBuilder sb = new StringBuilder();
        sb.append("FUNCTION_BLOCK \"FB_Unit").append(i).append("\"\n");
        sb.append("TITLE = Generated unit ").append(i).append('\n');
        sb.append("{ S7_Optimized_Access := 'TRUE' }\nAUTHOR : plca\nFAMILY : Bench\nVERSION : 1.")
                .append(i % 10)
                .append('\n');
        sb.append("""
                   VAR_INPUT
                      Enable : Bool;   // enable the unit
                      Setpoint : LReal;
                      Reset : Bool;
                   END_VAR

                   VAR_OUTPUT
                      Busy : Bool;
                      Error : Bool;
                      Status : Word;
                      Actual : LReal;
                   END_VAR

                   VAR
                      State : Int;
                      Timer {InstructionName := 'TON_TIME'; LibVersion := '1.0'} : TON_TIME;
                      Edge : R_TRIG;
                      Data : "UDT_Data";
                      Buffer { ExternalAccessible := 'False'; ExternalVisible := 'False'} : Array[0..31] of LReal;
                      Index : Int;
                      Sum : LReal;
                      Count : DInt;
                   END_VAR

                   VAR_TEMP
                      tempIndex : Int;
                      tempValue : LReal;
                   END_VAR

                   VAR CONSTANT
                      MAX_INDEX : Int := 31;
                   END_VAR


                BEGIN
                	REGION Inputs
                	    #Edge(CLK := #Reset);
                	    IF #Edge.Q THEN
                	        #State := 0;
                	        #Error := FALSE;
                	    END_IF;
                	END_REGION

                	REGION State machine
                	    CASE #State OF
                """);
        for (int s = 0; s < states; s++) {
            sb.append("\t        ").append(s).append(":\n");
            sb.append("\t            #Timer(IN := #Enable AND NOT #Error, PT := T#")
                    .append(100 + s * 50)
                    .append("ms);\n");
            sb.append("\t            IF #Timer.Q THEN\n");
            sb.append("\t                #State := ").append((s + 1) % states).append(";\n");
            sb.append("\t                #Status.%X").append(s % 16).append(" := TRUE;\n");
            sb.append("\t            ELSIF #Setpoint > ")
                    .append(rnd.nextInt(1000))
                    .append(".0 THEN\n");
            sb.append("\t                #Error := TRUE;\n");
            sb.append("\t                #Data.History[").append(s % 16).append("] := #State;\n");
            sb.append("\t            END_IF;\n");
        }
        sb.append("""
                	        ELSE
                	            #State := 0;
                	    END_CASE;
                	END_REGION

                	REGION Filter
                	    #Sum := 0.0;
                	    FOR #tempIndex := 0 TO #MAX_INDEX DO
                	        #tempValue := #Buffer[#tempIndex];
                	        IF #tempValue > 0.0 THEN
                	            #Sum := #Sum + #tempValue * 0.5;
                	        ELSE
                	            #Sum := #Sum - #tempValue;
                	        END_IF;
                	    END_FOR;
                	    #Buffer[#Index] := #Setpoint;
                	    #Index := (#Index + 1) MOD (#MAX_INDEX + 1);
                	    #Actual := #Sum / INT_TO_LREAL(#MAX_INDEX + 1);
                	    #Count += 1;
                	    "DB_Global".Values[
                """);
        sb.setLength(sb.length() - 1); // the index continues on the same line
        sb.append(i % 100).append("] := #Actual;\n");
        sb.append("""
                	END_REGION
                	#Busy := #State <> 0;
                	ENO := NOT #Error;
                END_FUNCTION_BLOCK
                """);
        return sb.toString();
    }
}
