// Developer tooling, never shipped: corpus check, parse debugging, JMH benchmarks.
plugins {
    id("plca.java")
}

dependencies {
    implementation(project(":frontend-st"))
    implementation(project(":frontend-scl"))
    implementation(project(":frontend-l5x"))
    implementation(project(":frontend-plcopen"))
    implementation(project(":plc-semantic"))
    implementation(project(":plc-rules"))
    implementation(project(":plc-analysis"))
}

val corpusDir =
    rootProject.layout.projectDirectory
        .dir("corpus")
        .asFile.absolutePath

// Parses the harvested corpus (corpus/external, git-ignored) per repository, builds the semantic model, and checks
// accept and resolution rates. Not part of `build`: the corpus is local-only; scripts/ci.sh runs it when present.
tasks.register<JavaExec>("corpusCheck") {
    group = "verification"
    description = "Parses the local PLC corpus and compares rates with corpus-baseline.properties."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.plca.devtools.CorpusCheck"
    maxHeapSize = "6g"
    val baseline =
        layout.projectDirectory
            .file("corpus-baseline.properties")
            .asFile.absolutePath
    val reports =
        layout.buildDirectory
            .dir("reports/corpus")
            .get()
            .asFile.absolutePath
    args(corpusDir, baseline, reports)
    if (providers.gradleProperty("updateBaseline").isPresent) args("--update-baseline")
    providers.gradleProperty("only").orNull?.let { args("--only=$it") }
}

// ./gradlew :plc-devtools:parseDebug --args="<file> ... | @listfile" prints diagnostics with source lines.
tasks.register<JavaExec>("parseDebug") {
    group = "verification"
    description = "Prints diagnostics for the given files (--args)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.plca.devtools.ParseDebug"
    workingDir = rootProject.projectDir
}

// ---- JMH benchmarks (src/jmh). No plugin: the Gradle JMH plugin's last release predates Gradle 9. ----
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

dependencies {
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.annprocess)
}

tasks.named<JavaCompile>("compileJmhJava") {
    // JMH-generated code is not lint-clean
    options.compilerArgs.removeAll(listOf("-Werror"))
}

tasks.named<Checkstyle>("checkstyleJmh") {
    exclude("**/jmh_generated/**")
}

// ./gradlew :plc-devtools:jmh [-Pjmh.args="ParseBenchmark -f 1"] [-Pjmh.corpus=<dir with .st/.TcPOU files>]
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    val jmhArgs = providers.gradleProperty("jmh.args").orElse("-f 1 -wi 3 -i 5 -rf json -rff build/reports/jmh/results.json")
    args(jmhArgs.get().split(" ").filter { it.isNotBlank() })
    providers.gradleProperty("jmh.corpus").orNull?.let { systemProperty("plca.bench.corpus", it) }
    val reportsDir =
        layout.buildDirectory
            .dir("reports/jmh")
            .get()
            .asFile
    doFirst { reportsDir.mkdirs() }
    workingDir = layout.projectDirectory.asFile
}
