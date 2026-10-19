// Web workbench (M9): Jetty serving the browser app, the language server over WebSocket and the project API.
// `plca web` in plc-cli starts it. The app itself is built from editors/web (npm) into src/main/resources/…/static
// by `./gradlew :editors:web:bundle`; without it the server shows a page that says how to build it.
plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-lsp"))
    implementation(libs.jetty.server)
    implementation(libs.jetty.websocket.server)
    implementation(libs.jackson.databind)
    // Jetty logs through SLF4J; route it to java.util.logging like the rest of plca
    runtimeOnly(libs.slf4j.jdk14)
    testImplementation(testFixtures(project(":plc-core")))
    testImplementation(libs.jazzer.junit)
}
