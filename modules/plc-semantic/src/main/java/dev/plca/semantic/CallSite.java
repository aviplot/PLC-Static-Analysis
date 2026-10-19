package dev.plca.semantic;

import dev.plca.core.source.Span;

/**
 * A call from one POU body to another POU. {@code instance} is the called FB instance (or the object whose method or
 * property is used), as an access path; null for functions.
 */
public record CallSite(PouSymbol caller, PouSymbol callee, Kind kind, Access instance, Span span) {

    public enum Kind {
        FUNCTION,
        /** {@code fbTimer(IN := x);} — runs the FB body. */
        FB_INSTANCE,
        METHOD,
        ACTION,
        PROPERTY_GET,
        PROPERTY_SET,
        /**
         * Rockwell {@code JSR}/{@code FOR}/{@code SFR} of a routine, a program's main/fault routine, an AOI scan mode.
         */
        ROUTINE
    }

    @Override
    public String toString() {
        return kind + " " + callee.qualifiedName() + (instance == null ? "" : " via " + instance.target());
    }
}
