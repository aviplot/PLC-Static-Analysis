package dev.plca.devtools.bench;

import java.util.Random;

/**
 * Deterministic generator of a representative Studio 5000 L5X controller export, so the benchmarks run without the
 * (local-only) corpus. The rung mix follows the corpus survey ({@code docs/grammar/rockwell.md}): mostly series
 * contacts driving a coil, about 11% with one branch and 1% nested, timers and one-shots, compares, MOV/ADD/CPT, module
 * I/O, AOI calls and JSRs; plus one ST routine per program.
 */
public final class SyntheticL5x {

    private SyntheticL5x() {}

    /** A controller with {@code programs} programs of {@code routines} routines of {@code rungs} rungs each. */
    public static String generate(int programs, int routines, int rungs, long seed) {
        Random rnd = new Random(seed);
        StringBuilder sb = new StringBuilder(programs * routines * rungs * 120);
        sb.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <RSLogix5000Content SchemaRevision="1.0" SoftwareRevision="35.00" TargetName="Bench" TargetType="Controller">
                <Controller Use="Target" Name="Bench" ProcessorType="1756-L83E">
                <DataTypes>
                <DataType Name="UDT_Unit" Family="NoFamily" Class="User">
                <Members>
                <Member Name="Run" DataType="BOOL" Dimension="0" Hidden="false" ExternalAccess="Read/Write"/>
                <Member Name="Fault" DataType="BOOL" Dimension="0" Hidden="false" ExternalAccess="Read/Write"/>
                <Member Name="Speed" DataType="REAL" Dimension="0" Hidden="false" ExternalAccess="Read/Write"/>
                <Member Name="Count" DataType="DINT" Dimension="0" Hidden="false" ExternalAccess="Read/Write"/>
                <Member Name="Bits" DataType="DINT" Dimension="8" Hidden="false" ExternalAccess="Read/Write"/>
                </Members>
                </DataType>
                </DataTypes>
                <AddOnInstructionDefinitions>
                <AddOnInstructionDefinition Name="Valve" Revision="1.0">
                <Parameters>
                <Parameter Name="EnableIn" TagType="Base" DataType="BOOL" Usage="Input" Required="false" Visible="false"/>
                <Parameter Name="EnableOut" TagType="Base" DataType="BOOL" Usage="Output" Required="false" Visible="false"/>
                <Parameter Name="Cmd" TagType="Base" DataType="BOOL" Usage="Input" Required="true" Visible="true"/>
                <Parameter Name="Open" TagType="Base" DataType="BOOL" Usage="Output" Required="true" Visible="true"/>
                </Parameters>
                <LocalTags><LocalTag Name="Tmr" DataType="TIMER"/></LocalTags>
                <Routines><Routine Name="Logic" Type="RLL"><RLLContent>
                <Rung Number="0" Type="N"><Text><![CDATA[XIC(Cmd)TON(Tmr,?,?)XIC(Tmr.DN)OTE(Open);]]></Text></Rung>
                </RLLContent></Routine></Routines>
                </AddOnInstructionDefinition>
                </AddOnInstructionDefinitions>
                <Tags>
                """);
        for (int i = 0; i < 200; i++) {
            sb.append("<Tag Name=\"B")
                    .append(i)
                    .append("\" TagType=\"Base\" DataType=\"BOOL\" ExternalAccess=\"Read/Write\"/>\n");
            sb.append("<Tag Name=\"U")
                    .append(i)
                    .append("\" TagType=\"Base\" DataType=\"UDT_Unit\" ExternalAccess=\"Read/Write\"/>\n");
            sb.append("<Tag Name=\"T")
                    .append(i)
                    .append("\" TagType=\"Base\" DataType=\"TIMER\" ExternalAccess=\"Read/Write\"/>\n");
            sb.append("<Tag Name=\"V")
                    .append(i)
                    .append("\" TagType=\"Base\" DataType=\"Valve\" ExternalAccess=\"Read/Write\"/>\n");
        }
        sb.append("<Tag Name=\"OS\" TagType=\"Base\" DataType=\"BOOL\" Dimensions=\"256\" ExternalAccess=\"None\"/>\n");
        sb.append("</Tags>\n<Programs>\n");
        for (int p = 0; p < programs; p++) {
            sb.append("<Program Name=\"P").append(p).append("\" MainRoutineName=\"R0\">\n<Tags/>\n<Routines>\n");
            for (int r = 0; r < routines; r++) {
                sb.append("<Routine Name=\"R").append(r).append("\" Type=\"RLL\">\n<RLLContent>\n");
                for (int n = 0; n < rungs; n++) {
                    sb.append("<Rung Number=\"").append(n).append("\" Type=\"N\">\n<Text>\n<![CDATA[");
                    if (r == 0 && n < routines - 1) {
                        sb.append("JSR(R").append(n + 1).append(",0);");
                    } else {
                        rung(sb, rnd);
                    }
                    sb.append("]]>\n</Text>\n</Rung>\n");
                }
                sb.append("</RLLContent>\n</Routine>\n");
            }
            sb.append("<Routine Name=\"Calc\" Type=\"ST\">\n<STContent>\n");
            String[] st = {
                "IF U1.Run AND NOT U1.Fault THEN",
                "    U1.Speed := U1.Speed + 0.5 * U2.Speed;",
                "ELSIF U3.Count > 10 THEN",
                "    U3.Count := 0;",
                "END_IF;",
                "FOR i := 0 TO 7 DO U4.Bits[i] := U4.Bits[i] + 1; END_FOR;",
                "COP(U5.Bits[0], U6.Bits[0], 8);"
            };
            for (int k = 0; k < st.length; k++) {
                sb.append("<Line Number=\"")
                        .append(k)
                        .append("\">\n<![CDATA[")
                        .append(st[k])
                        .append("]]>\n</Line>\n");
            }
            sb.append("</STContent>\n</Routine>\n</Routines>\n</Program>\n");
        }
        sb.append(
                "</Programs>\n<Tasks>\n<Task Name=\"Main\" Type=\"CONTINUOUS\" Priority=\"10\">\n<ScheduledPrograms>\n");
        for (int p = 0; p < programs; p++) {
            sb.append("<ScheduledProgram Name=\"P").append(p).append("\"/>\n");
        }
        sb.append("</ScheduledPrograms>\n</Task>\n</Tasks>\n</Controller>\n</RSLogix5000Content>\n");
        return sb.toString();
    }

    private static void rung(StringBuilder sb, Random rnd) {
        int kind = rnd.nextInt(100);
        if (kind < 11) {
            sb.append('[');
            contacts(sb, rnd, 1 + rnd.nextInt(2));
            sb.append(" ,");
            if (kind < 2) {
                sb.append('[');
                contacts(sb, rnd, 1);
                sb.append(" ,");
                contacts(sb, rnd, 1);
                sb.append(" ]");
            } else {
                contacts(sb, rnd, 1);
            }
            sb.append(" ]");
        } else {
            contacts(sb, rnd, 1 + rnd.nextInt(4));
        }
        int out = rnd.nextInt(100);
        if (out < 45) {
            sb.append("OTE(B").append(rnd.nextInt(200)).append(')');
        } else if (out < 55) {
            sb.append(rnd.nextBoolean() ? "OTL(" : "OTU(")
                    .append('U')
                    .append(rnd.nextInt(200))
                    .append(".Run)");
        } else if (out < 65) {
            sb.append("TON(T").append(rnd.nextInt(200)).append(",?,?)");
        } else if (out < 73) {
            sb.append("ONS(OS[")
                    .append(rnd.nextInt(256))
                    .append("])ADD(U")
                    .append(rnd.nextInt(200))
                    .append(".Count,1,U")
                    .append(rnd.nextInt(200))
                    .append(".Count)");
        } else if (out < 82) {
            sb.append("MOV(U")
                    .append(rnd.nextInt(200))
                    .append(".Speed,U")
                    .append(rnd.nextInt(200))
                    .append(".Speed)");
        } else if (out < 86) {
            sb.append("CPT(U")
                    .append(rnd.nextInt(200))
                    .append(".Speed,(U")
                    .append(rnd.nextInt(200))
                    .append(".Speed - 4.0) / 16.0 * 100.0)");
        } else if (out < 92) {
            sb.append("Valve(V")
                    .append(rnd.nextInt(200))
                    .append(",B")
                    .append(rnd.nextInt(200))
                    .append(",B")
                    .append(rnd.nextInt(200))
                    .append(')');
        } else {
            sb.append("OTE(Local:2:O.Data.").append(rnd.nextInt(16)).append(')');
        }
        sb.append(';');
    }

    private static void contacts(StringBuilder sb, Random rnd, int n) {
        for (int i = 0; i < n; i++) {
            int c = rnd.nextInt(100);
            if (c < 55) {
                sb.append("XIC(B").append(rnd.nextInt(200)).append(')');
            } else if (c < 75) {
                sb.append("XIO(U").append(rnd.nextInt(200)).append(".Fault)");
            } else if (c < 85) {
                sb.append("XIC(T").append(rnd.nextInt(200)).append(".DN)");
            } else if (c < 92) {
                sb.append("EQU(U")
                        .append(rnd.nextInt(200))
                        .append(".Count,")
                        .append(rnd.nextInt(10))
                        .append(')');
            } else {
                sb.append("XIC(Local:1:I.Data.").append(rnd.nextInt(32)).append(')');
            }
        }
    }
}
