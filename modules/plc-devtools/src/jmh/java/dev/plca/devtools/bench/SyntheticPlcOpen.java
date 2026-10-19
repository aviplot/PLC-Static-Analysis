package dev.plca.devtools.bench;

/**
 * Deterministic generator of a PLCopen TC6 2.01 export for the benchmarks: per unit one ST function block, one LD
 * program (seal-in rungs, a timer, set/reset coils), one FBD program (functions and an FB) and one SFC program (steps,
 * a selection branch, actions), plus a configuration with tasks.
 */
public final class SyntheticPlcOpen {

    private SyntheticPlcOpen() {}

    public static String generate(int units, int rungs) {
        StringBuilder sb = new StringBuilder(units * rungs * 900);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<project xmlns=\"http://www.plcopen.org/xml/tc6_0201\""
                + " xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n<fileHeader companyName=\"Bench\" productName=\"Bench\"/>\n"
                + "<types><dataTypes/><pous>\n");
        for (int u = 0; u < units; u++) {
            sb.append("<pou name=\"FB_St")
                    .append(u)
                    .append("\" pouType=\"functionBlock\"><interface>")
                    .append("<inputVars><variable name=\"In\"><type><INT/></type></variable></inputVars>")
                    .append("<outputVars><variable name=\"Out\"><type><INT/></type></variable></outputVars>")
                    .append("<localVars><variable name=\"i\"><type><INT/></type></variable></localVars></interface>")
                    .append("<body><ST><xhtml:p><![CDATA[Out := 0;\nFOR i := 1 TO In DO\n  Out := Out + i * 2;\n")
                    .append("END_FOR;\nIF Out > 100 THEN Out := 100; END_IF;]]></xhtml:p></ST></body></pou>\n");
            sb.append("<pou name=\"Ld").append(u).append("\" pouType=\"program\"><interface><localVars>");
            for (int k = 0; k < 8; k++) {
                sb.append("<variable name=\"b").append(k).append("\"><type><BOOL/></type></variable>");
            }
            sb.append("<variable name=\"T1\"><type><derived name=\"TON\"/></type></variable></localVars></interface>")
                    .append("<body><LD>\n<leftPowerRail localId=\"1\"><position x=\"0\" y=\"0\"/><connectionPointOut/>")
                    .append("</leftPowerRail>\n");
            int id = 2;
            for (int r = 0; r < rungs; r++) {
                int y = r * 100;
                int a = id++;
                int b = id++;
                int c = id++;
                int coil = id++;
                sb.append(contact(a, "b" + (r % 8), 1, y));
                sb.append(contact(b, "b" + ((r + 1) % 8), 1, y + 30));
                sb.append("<contact localId=\"")
                        .append(c)
                        .append("\" negated=\"true\"><position x=\"100\" y=\"")
                        .append(y)
                        .append("\"/><connectionPointIn><connection refLocalId=\"")
                        .append(a)
                        .append("\"/><connection refLocalId=\"")
                        .append(b)
                        .append("\"/></connectionPointIn><connectionPointOut/><variable>b")
                        .append((r + 2) % 8)
                        .append("</variable></contact>\n");
                sb.append("<coil localId=\"")
                        .append(coil)
                        .append("\" storage=\"")
                        .append(r % 5 == 0 ? "set" : "none")
                        .append("\"><position x=\"200\" y=\"")
                        .append(y)
                        .append("\"/><connectionPointIn><connection refLocalId=\"")
                        .append(c)
                        .append("\"/></connectionPointIn><connectionPointOut/><variable>b")
                        .append((r + 3) % 8)
                        .append("</variable></coil>\n");
            }
            sb.append("</LD></body></pou>\n");
            sb.append("<pou name=\"Fbd")
                    .append(u)
                    .append("\" pouType=\"program\"><interface><localVars>")
                    .append("<variable name=\"x\"><type><REAL/></type></variable>")
                    .append("<variable name=\"y\"><type><REAL/></type></variable>")
                    .append("<variable name=\"f\"><type><derived name=\"FB_St")
                    .append(u)
                    .append("\"/></type></variable></localVars></interface><body><FBD>\n")
                    .append("<inVariable localId=\"1\"><position x=\"0\" y=\"0\"/><connectionPointOut/><expression>x")
                    .append("</expression></inVariable>\n")
                    .append(
                            "<inVariable localId=\"2\"><position x=\"0\" y=\"20\"/><connectionPointOut/><expression>2.5")
                    .append("</expression></inVariable>\n")
                    .append("<block localId=\"3\" typeName=\"MUL\"><position x=\"50\" y=\"0\"/><inputVariables>")
                    .append("<variable formalParameter=\"IN1\"><connectionPointIn><connection refLocalId=\"1\"/>")
                    .append("</connectionPointIn></variable><variable formalParameter=\"IN2\"><connectionPointIn>")
                    .append("<connection refLocalId=\"2\"/></connectionPointIn></variable></inputVariables>")
                    .append("<inOutVariables/><outputVariables><variable formalParameter=\"OUT\"><connectionPointOut/>")
                    .append("</variable></outputVariables></block>\n")
                    .append("<outVariable localId=\"4\"><position x=\"100\" y=\"0\"/><connectionPointIn>")
                    .append("<connection refLocalId=\"3\" formalParameter=\"OUT\"/></connectionPointIn><expression>y")
                    .append("</expression></outVariable>\n</FBD></body></pou>\n");
            sb.append("<pou name=\"Sfc")
                    .append(u)
                    .append("\" pouType=\"program\"><interface><localVars>")
                    .append("<variable name=\"go\"><type><BOOL/></type></variable>")
                    .append("<variable name=\"n\"><type><INT/></type></variable></localVars></interface><body><SFC>\n")
                    .append("<step localId=\"1\" name=\"A\" initialStep=\"true\"><connectionPointOut/></step>\n")
                    .append("<transition localId=\"2\"><connectionPointIn><connection refLocalId=\"1\"/>")
                    .append("</connectionPointIn><condition><inline name=\"\"><ST><xhtml:p><![CDATA[go]]></xhtml:p>")
                    .append("</ST></inline></condition></transition>\n")
                    .append("<step localId=\"3\" name=\"B\"><connectionPointIn><connection refLocalId=\"2\"/>")
                    .append("</connectionPointIn></step>\n")
                    .append("<actionBlock localId=\"4\"><connectionPointIn><connection refLocalId=\"3\"/>")
                    .append("</connectionPointIn><action qualifier=\"N\"><inline><ST><xhtml:p><![CDATA[n := n + 1;]]>")
                    .append("</xhtml:p></ST></inline></action></actionBlock>\n")
                    .append("<transition localId=\"5\"><connectionPointIn><connection refLocalId=\"3\"/>")
                    .append("</connectionPointIn><condition><inline name=\"\"><ST><xhtml:p><![CDATA[NOT go]]>")
                    .append("</xhtml:p></ST></inline></condition></transition>\n")
                    .append(
                            "<jumpStep localId=\"6\" targetName=\"A\"><connectionPointIn><connection refLocalId=\"5\"/>")
                    .append("</connectionPointIn></jumpStep>\n</SFC></body></pou>\n");
        }
        sb.append("</pous></types>\n<instances><configurations><configuration name=\"c\"><resource name=\"r\">")
                .append("<task name=\"t\" priority=\"1\" interval=\"T#10ms\">");
        for (int u = 0; u < units; u++) {
            sb.append("<pouInstance name=\"l")
                    .append(u)
                    .append("\" typeName=\"Ld")
                    .append(u)
                    .append("\"/>");
        }
        sb.append("</task></resource></configuration></configurations></instances>\n</project>\n");
        return sb.toString();
    }

    private static String contact(int id, String variable, int source, int y) {
        return "<contact localId=\"" + id + "\" negated=\"false\"><position x=\"50\" y=\"" + y
                + "\"/><connectionPointIn><connection refLocalId=\"" + source
                + "\"/></connectionPointIn><connectionPointOut/><variable>" + variable + "</variable></contact>\n";
    }
}
