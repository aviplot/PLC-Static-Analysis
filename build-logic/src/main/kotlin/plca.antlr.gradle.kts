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

tasks.generateGrammarSource {
    arguments.addAll(listOf("-visitor", "-no-listener", "-Werror"))
}

tasks.withType<JavaCompile>().configureEach {
    // Generated code is not ours to keep lint-clean.
    options.compilerArgs.removeAll(listOf("-Werror"))
}

tasks.withType<Checkstyle>().configureEach {
    exclude { it.file.path.contains("generated-src") }
}
