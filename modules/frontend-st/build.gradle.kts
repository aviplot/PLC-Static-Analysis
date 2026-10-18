plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-ast"))
    implementation(project(":plc-grammar"))
    implementation(libs.woodstox)
    testImplementation(testFixtures(project(":plc-core")))
    testImplementation(libs.jazzer.junit)
}

// Parses the harvested corpus (corpus/external, git-ignored) and checks accept rate and SLL fallbacks.
// Not part of `build`: the corpus is local-only. scripts/ci.sh runs it when the corpus exists.
tasks.register<JavaExec>("corpusCheck") {
    group = "verification"
    description = "Parses the local PLC corpus and compares accept rates with corpus-baseline.properties."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.plca.frontend.st.corpus.CorpusCheck"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    maxHeapSize = "4g"
    val corpusDir =
        rootProject.layout.projectDirectory
            .dir("corpus")
            .asFile.absolutePath
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
}

// ./gradlew :frontend-st:parseDebug --args="<file> ..." prints diagnostics with source lines.
tasks.register<JavaExec>("parseDebug") {
    group = "verification"
    description = "Prints diagnostics for the given files (--args)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.plca.frontend.st.corpus.ParseDebug"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    workingDir = rootProject.projectDir
}
