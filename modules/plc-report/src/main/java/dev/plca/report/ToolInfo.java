package dev.plca.report;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Name and version of the analyzer as written into reports. */
public final class ToolInfo {

    public static final String NAME = "plca";
    public static final String VERSION = loadVersion();

    private ToolInfo() {}

    private static String loadVersion() {
        try (InputStream in = ToolInfo.class.getResourceAsStream("/dev/plca/report/version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
