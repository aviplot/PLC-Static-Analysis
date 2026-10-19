// ANTLR 4 grammar module (applied by plc-grammar from M1 on).

plugins {
    id("plca.java")
    antlr
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    antlr(libs.findLibrary("antlr-tool").get())
    // Generated parsers extend runtime classes, so consumers need it; frontends depend on this
    // module via `implementation`, which stops the runtime from leaking past them.
    api(libs.findLibrary("antlr-runtime").get())
}

// The antlr plugin makes `api` extend `antlr`, which would leak the full tool (and its
// dependencies) to every consumer. Keep only the runtime declared above.
configurations.api {
    setExtendsFrom(extendsFrom.filterNot { it.name == "antlr" })
}

// Shared core grammars (St*.g4) sit next to the dialect grammars that import them, but are never
// generated on their own: a core grammar alone has no channels, options or token vocabulary.
tasks.generateGrammarSource {
    exclude("StLexer.g4", "StParser.g4")
    // Every grammar is also a non-source input, so any grammar change regenerates all of them (a non-source input
    // change makes the task run non-incrementally). Needed twice over: the excluded core grammars are no longer
    // task inputs at all, and a parser takes its token types from its lexer's .tokens file (tokenVocab), so
    // regenerating only a changed lexer leaves its parser with stale token numbers.
    inputs
        .files(
            layout.projectDirectory
                .dir("src/main/antlr")
                .asFileTree
                .matching { include("*.g4") },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
    packageName = "dev.plca.grammar"
    arguments.addAll(listOf("-visitor", "-no-listener", "-Werror"))
}

tasks.withType<JavaCompile>().configureEach {
    // Generated code is not ours to keep lint-clean.
    options.compilerArgs.removeAll(listOf("-Werror"))
}

tasks.withType<Checkstyle>().configureEach {
    exclude { it.file.path.contains("generated-src") }
}
