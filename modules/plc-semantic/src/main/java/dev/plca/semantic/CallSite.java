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
        PROPERTY_SET
    }

    @Override
    public String toString() {
        return kind + " " + callee.qualifiedName() + (instance == null ? "" : " via " + instance.target());
    }
}
