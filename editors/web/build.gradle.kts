// The web workbench's browser app (M9): npm-driven, not part of `build` (Node is needed only for these tasks).
// `./gradlew :editors:web:bundle` writes dist/, which plc-web packs into its jar when present; then
// `./gradlew :plc-cli:installDist` gives `plca web` with the app. `unitTest` and `e2eTest` run the app's tests.

val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val npmCi by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs the app's npm dependencies from package-lock.json."
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
    commandLine(npm, "ci", "--no-audit", "--no-fund")
}

tasks.register<Exec>("bundle") {
    group = "build"
    description = "Type-checks and bundles the workbench app into dist/."
    dependsOn(npmCi)
    inputs.dir("src")
    inputs.file("package-lock.json")
    inputs.file("scripts/build.mjs")
    outputs.dir("dist")
    commandLine(npm, "run", "bundle")
}

tasks.register<Exec>("unitTest") {
    group = "verification"
    description = "Runs the app's unit tests (node --test)."
    dependsOn(npmCi)
    commandLine(npm, "run", "test:unit")
}

tasks.register<Exec>("e2eTest") {
    group = "verification"
    description = "Runs the Playwright tests against plca web (needs Google Chrome; a display is not needed)."
    dependsOn("bundle", ":plc-cli:installDist")
    commandLine(npm, "run", "test:e2e")
}
