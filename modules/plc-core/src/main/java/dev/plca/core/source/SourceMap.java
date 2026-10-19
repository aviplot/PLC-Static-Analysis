package dev.plca.core.source;

import java.util.Arrays;

/**
 * Maps offsets in an extracted fragment back to offsets in its physical {@link SourceFile}.
 *
 * <p>The map is a sorted list of segments {@code (fragmentStart, fileStart, fragmentLength, fileLength)}. A segment
 * with equal lengths is <em>verbatim</em> (CDATA content, plain character data) and maps character by character. A
 * segment with different lengths is a <em>replacement</em>: an entity reference such as {@code &lt;}, a character
 * reference, or a {@code \r\n} that the XML parser normalised to {@code \n}. Any fragment offset inside a replacement
 * maps to the start of the original construct, and a span ending inside it is widened to cover all of it.
 *
 * <p>Gaps in the file between segments (CDATA markers, comments) belong to no fragment offset.
 */
public final class SourceMap {

    private final int[] fragStart;
    private final int[] fileStart;
    private final int[] fragLen;
    private final int[] fileLen;
    private final int fragmentLength;

    private SourceMap(int[] fragStart, int[] fileStart, int[] fragLen, int[] fileLen, int fragmentLength) {
        this.fragStart = fragStart;
        this.fileStart = fileStart;
        this.fragLen = fragLen;
        this.fileLen = fileLen;
        this.fragmentLength = fragmentLength;
    }

    /**
     * Identity map for a text that is the whole file (or a verbatim copy of a region starting at {@code fileStart}).
     */
    public static SourceMap verbatim(int fileStart, int length) {
        return builder().verbatim(fileStart, length).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int fragmentLength() {
        return fragmentLength;
    }

    /** File offset of the fragment character at {@code offset}; {@code offset == fragmentLength()} maps to the end. */
    public int toFileStart(int offset) {
        checkOffset(offset);
        if (offset == fragmentLength) {
            return toFileEnd(offset);
        }
        int i = segmentContaining(offset);
        return isVerbatim(i) ? fileStart[i] + (offset - fragStart[i]) : fileStart[i];
    }

    /** File offset for an exclusive end position, so {@code [toFileStart(s), toFileEnd(e))} covers the span. */
    public int toFileEnd(int offset) {
        checkOffset(offset);
        if (fragStart.length == 0) {
            return 0;
        }
        if (offset == 0) {
            return fileStart[0];
        }
        int i = segmentContaining(offset - 1);
        return isVerbatim(i) ? fileStart[i] + (offset - fragStart[i]) : fileStart[i] + fileLen[i];
    }

    /**
     * The inverse: the fragment offset of file offset {@code fileOffset}, or -1 if the file character belongs to no
     * fragment character (CDATA markers, other XML between segments, outside the fragment). An offset inside a
     * replacement maps to the start of its produced text. The end of a segment maps to the fragment offset after it, so
     * a cursor directly after the last character of a fragment still finds it.
     */
    public int toFragment(int fileOffset) {
        int n = fileStart.length;
        int i = Arrays.binarySearch(fileStart, 0, n, fileOffset);
        if (i < 0) {
            i = -i - 2;
        } else {
            // file offsets are non-decreasing but zero-length file ranges (inserted text) can repeat one: take the last
            while (i + 1 < n && fileStart[i + 1] == fileOffset) {
                i++;
            }
        }
        if (i < 0) {
            return -1;
        }
        int rel = fileOffset - fileStart[i];
        if (rel < fileLen[i]) {
            return isVerbatim(i) ? fragStart[i] + rel : fragStart[i];
        }
        return rel == fileLen[i] ? fragStart[i] + fragLen[i] : -1;
    }

    /** File offset where the fragment starts (its first segment), or -1 for an empty fragment. */
    public int fileFrom() {
        return fileStart.length == 0 ? -1 : fileStart[0];
    }

    /** File offset after the fragment's last segment, or -1 for an empty fragment. */
    public int fileTo() {
        int n = fileStart.length;
        return n == 0 ? -1 : fileStart[n - 1] + fileLen[n - 1];
    }

    private boolean isVerbatim(int i) {
        return fragLen[i] == fileLen[i];
    }

    private int segmentContaining(int offset) {
        int i = Arrays.binarySearch(fragStart, offset);
        if (i < 0) {
            i = -i - 2;
        }
        // zero-length fragment segments cannot occur (builder drops them), so this is the unique owner
        return i;
    }

    private void checkOffset(int offset) {
        if (offset < 0 || offset > fragmentLength) {
            throw new IndexOutOfBoundsException("offset " + offset + " of fragment length " + fragmentLength);
        }
    }

    /** Appends segments in fragment order; file offsets must be non-decreasing. */
    public static final class Builder {
        private int[] fragStart = new int[8];
        private int[] fileStart = new int[8];
        private int[] fragLen = new int[8];
        private int[] fileLen = new int[8];
        private int count;
        private int fragPos;
        private int lastFileEnd;

        private Builder() {}

        /** {@code length} fragment characters copied unchanged from {@code fileOffset}. */
        public Builder verbatim(int fileOffset, int length) {
            if (length == 0) {
                return this;
            }
            // merge with a directly preceding verbatim run
            if (count > 0
                    && fragLen[count - 1] == fileLen[count - 1]
                    && fileStart[count - 1] + fileLen[count - 1] == fileOffset) {
                fragLen[count - 1] += length;
                fileLen[count - 1] += length;
                fragPos += length;
                lastFileEnd = fileOffset + length;
                return this;
            }
            return add(fileOffset, length, length);
        }

        /** {@code producedLength} fragment characters that replace {@code fileLength} file characters. */
        public Builder replacement(int fileOffset, int fileLength, int producedLength) {
            if (producedLength == 0) {
                return this;
            }
            if (fileLength == producedLength) {
                return verbatim(fileOffset, producedLength);
            }
            return add(fileOffset, fileLength, producedLength);
        }

        private Builder add(int fileOffset, int fileLength, int producedLength) {
            if (fileOffset < lastFileEnd) {
                throw new IllegalArgumentException("file offsets must not go backwards: " + fileOffset);
            }
            if (count == fragStart.length) {
                int n = count * 2;
                fragStart = Arrays.copyOf(fragStart, n);
                fileStart = Arrays.copyOf(fileStart, n);
                fragLen = Arrays.copyOf(fragLen, n);
                fileLen = Arrays.copyOf(fileLen, n);
            }
            fragStart[count] = fragPos;
            fileStart[count] = fileOffset;
            fragLen[count] = producedLength;
            fileLen[count] = fileLength;
            count++;
            fragPos += producedLength;
            lastFileEnd = fileOffset + fileLength;
            return this;
        }

        public SourceMap build() {
            return new SourceMap(
                    Arrays.copyOf(fragStart, count),
                    Arrays.copyOf(fileStart, count),
                    Arrays.copyOf(fragLen, count),
                    Arrays.copyOf(fileLen, count),
                    fragPos);
        }
    }
}
