// Language server (M8): LSP4J over the shared analysis pipeline; `plca lsp` in plc-cli starts it.
plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-analysis"))
    api(libs.lsp4j)
    constraints {
        // LSP4J asks for gson [2.9.1,3.0): pin it so builds do not change when Gson releases
        implementation(libs.gson) { version { strictly(libs.versions.gson.get()) } }
    }
    testImplementation(testFixtures(project(":plc-core")))
}
