package dev.plca.report;

import java.io.IOException;
import java.io.Writer;

/** Output formats of {@code plca analyze --format}. */
public enum ReportFormat {
    CONSOLE,
    JSON,
    SARIF;

    public void write(AnalysisResult result, Writer out) throws IOException {
        switch (this) {
            case CONSOLE -> new ConsoleReport(false).write(result, out);
            case JSON -> JsonReport.write(result, out);
            case SARIF -> SarifReport.write(result, out);
        }
    }
}
