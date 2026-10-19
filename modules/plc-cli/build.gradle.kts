plugins {
    id("plca.cli")
}

dependencies {
    implementation(project(":plc-analysis"))
    implementation(project(":plc-lsp"))
    implementation(project(":plc-web"))
    testImplementation(libs.jackson.databind)
}

application {
    applicationName = "plca"
    mainClass = "dev.plca.cli.Main"
}

// Runs the installed start script on the M2 test project: catches packaging problems (missing resources, classpath)
// that in-process tests cannot see. Exit code 1 is expected there (the project has an error-level finding).
val smokeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs build/install/plca/bin/plca on the plant test project."
    dependsOn(tasks.named("installDist"))
    val script =
        layout.buildDirectory
            .file("install/plca/bin/plca")
            .get()
            .asFile
    val project = rootProject.layout.projectDirectory.dir("modules/plc-semantic/src/test/resources/projects/plant")
    val report =
        layout.buildDirectory
            .file("smoke/plant.sarif")
            .get()
            .asFile
    inputs.dir(project)
    outputs.file(report)
    commandLine(script.absolutePath, "analyze", project.asFile.absolutePath, "--format", "sarif", "--output", report.absolutePath)
    isIgnoreExitValue = true
    doLast {
        val code = executionResult.get().exitValue
        check(code == 1) { "plca exited with $code, expected 1" }
        check(report.readText().contains("\"PLCA-Q007\"")) { "SARIF report lacks the expected recursion finding" }
    }
}

tasks.named("check") { dependsOn(smokeTest) }
