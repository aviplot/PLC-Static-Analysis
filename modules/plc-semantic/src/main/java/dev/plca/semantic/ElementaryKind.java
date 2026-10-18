package dev.plca.semantic;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** IEC 61131-3 elementary types plus the CODESYS additions (IEC 6.4.2). */
public enum ElementaryKind {
    BOOL(Category.BOOL, 1, false),
    /** CODESYS {@code BIT}: a single bit in a struct; behaves like BOOL. */
    BIT(Category.BOOL, 1, false),
    BYTE(Category.BIT_STRING, 8, false),
    WORD(Category.BIT_STRING, 16, false),
    DWORD(Category.BIT_STRING, 32, false),
    LWORD(Category.BIT_STRING, 64, false),
    /** CODESYS {@code __XWORD}: platform-sized bit string (pointer size). Modelled as 64 bits. */
    XWORD(Category.BIT_STRING, 64, false),
    SINT(Category.SIGNED, 8, true),
    INT(Category.SIGNED, 16, true),
    DINT(Category.SIGNED, 32, true),
    LINT(Category.SIGNED, 64, true),
    /** CODESYS {@code __XINT}: platform-sized signed integer. Modelled as 64 bits. */
    XINT(Category.SIGNED, 64, true),
    USINT(Category.UNSIGNED, 8, false),
    UINT(Category.UNSIGNED, 16, false),
    UDINT(Category.UNSIGNED, 32, false),
    ULINT(Category.UNSIGNED, 64, false),
    /** CODESYS {@code __UXINT}: platform-sized unsigned integer. Modelled as 64 bits. */
    UXINT(Category.UNSIGNED, 64, false),
    REAL(Category.REAL, 32, true),
    LREAL(Category.REAL, 64, true),
    TIME(Category.DURATION, 32, true),
    LTIME(Category.DURATION, 64, true),
    DATE(Category.DATE, 32, false),
    LDATE(Category.DATE, 64, false),
    TIME_OF_DAY(Category.DATE, 32, false),
    LTIME_OF_DAY(Category.DATE, 64, false),
    DATE_AND_TIME(Category.DATE, 32, false),
    LDATE_AND_TIME(Category.DATE, 64, false),
    CHAR(Category.CHAR, 8, false),
    WCHAR(Category.CHAR, 16, false);

    public enum Category {
        BOOL,
        BIT_STRING,
        SIGNED,
        UNSIGNED,
        REAL,
        DURATION,
        DATE,
        CHAR
    }

    private final Category category;
    private final int bits;
    private final boolean signed;

    ElementaryKind(Category category, int bits, boolean signed) {
        this.category = category;
        this.bits = bits;
        this.signed = signed;
    }

    public Category category() {
        return category;
    }

    public int bits() {
        return bits;
    }

    public boolean signed() {
        return signed;
    }

    public boolean isInteger() {
        return category == Category.SIGNED || category == Category.UNSIGNED;
    }

    public boolean isReal() {
        return category == Category.REAL;
    }

    public boolean isNumeric() {
        return isInteger() || isReal();
    }

    public boolean isBitString() {
        return category == Category.BIT_STRING;
    }

    /** Smallest and largest representable integer values ({@code ULINT} max is clamped to {@code Long.MAX_VALUE}). */
    public long minValue() {
        if (!isInteger() && !isBitString()) {
            throw new IllegalStateException(this + " is not an integer type");
        }
        return signed ? (bits == 64 ? Long.MIN_VALUE : -(1L << (bits - 1))) : 0;
    }

    public long maxValue() {
        if (!isInteger() && !isBitString()) {
            throw new IllegalStateException(this + " is not an integer type");
        }
        if (bits == 64) {
            return Long.MAX_VALUE;
        }
        return signed ? (1L << (bits - 1)) - 1 : (1L << bits) - 1;
    }

    private static final Map<String, ElementaryKind> BY_NAME = new HashMap<>();

    static {
        for (ElementaryKind k : values()) {
            BY_NAME.put(k.name(), k);
        }
        BY_NAME.put("TOD", TIME_OF_DAY);
        BY_NAME.put("LTOD", LTIME_OF_DAY);
        BY_NAME.put("DT", DATE_AND_TIME);
        BY_NAME.put("LDT", LDATE_AND_TIME);
        BY_NAME.put("__XWORD", XWORD);
        BY_NAME.put("__XINT", XINT);
        BY_NAME.put("__UXINT", UXINT);
        BY_NAME.remove("XINT");
        BY_NAME.remove("UXINT");
    }

    /** Looks up a type name as written in source ({@code INT}, {@code tod}, {@code __UXINT}). */
    public static Optional<ElementaryKind> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name.toUpperCase(Locale.ROOT)));
    }
}
