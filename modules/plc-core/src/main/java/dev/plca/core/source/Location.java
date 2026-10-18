package dev.plca.core.source;

/**
 * A resolved position in a physical file, as shown in the IDE and written to reports. Lines and columns are 1-based and
 * count UTF-16 code units; {@code endColumn} is exclusive (the column after the last character), matching SARIF's
 * {@code region.endColumn}. LSP consumers subtract one from each component.
 */
public record Location(String path, int startLine, int startColumn, int endLine, int endColumn) {

    @Override
    public String toString() {
        return path + ":" + startLine + ":" + startColumn + "-" + endLine + ":" + endColumn;
    }
}
