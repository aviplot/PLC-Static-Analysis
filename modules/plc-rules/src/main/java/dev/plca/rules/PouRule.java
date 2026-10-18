package dev.plca.rules;

/** A rule that looks at one POU body at a time. The engine runs these in parallel over POUs. */
public non-sealed interface PouRule extends Rule {

    void check(PouContext ctx);
}
