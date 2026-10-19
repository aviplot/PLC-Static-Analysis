// The VS Code extension: bundles the plca server jars and drives npm. Not part of `build`, so the Java build needs no
// Node. `./gradlew :editors:vscode:package` builds plca.vsix; `grammarTest` and `e2eTest` run the extension's tests.

val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val serverLib = layout.projectDirectory.dir("server/lib")

val copyServer by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies the plca jars (installDist) into server/lib of the extension."
    dependsOn(":plc-cli:installDist")
    from(project(":plc-cli").layout.buildDirectory.dir("install/plca/lib"))
    into(serverLib)
}

val npmCi by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the extension's npm dependencies from package-lock.json."
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
    commandLine(npm, "ci", "--no-audit", "--no-fund")
}

tasks.register<Exec>("package") {
    group = "build"
    description = "Builds plca.vsix with the bundled server."
    dependsOn(copyServer, npmCi)
    commandLine(npm, "run", "package")
}

tasks.register<Exec>("grammarTest") {
    group = "verification"
    description = "Runs the TextMate grammar snapshot tests."
    dependsOn(npmCi)
    commandLine(npm, "run", "test:grammar")
}

tasks.register<Exec>("e2eTest") {
    group = "verification"
    description = "Runs the smoke test in VS Code (VSCODE_EXECUTABLE: an installed VS Code; needs a display)."
    dependsOn(copyServer, npmCi)
    commandLine(npm, "run", "test:e2e")
}
