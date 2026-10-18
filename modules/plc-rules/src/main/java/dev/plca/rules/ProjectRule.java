package dev.plca.rules;

/** A rule that needs the whole project (writes across POUs, call-graph cycles, unused globals). Runs once. */
public non-sealed interface ProjectRule extends Rule {

    void check(ProjectContext ctx);
}
