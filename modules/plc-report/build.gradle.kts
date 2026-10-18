plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-rules"))
    implementation(libs.jackson.databind)
    testImplementation(project(":frontend-st"))
    testImplementation(testFixtures(project(":plc-core")))
    testImplementation(libs.json.schema.validator)
}

// version.properties carries the build version into reports (SARIF tool.driver.version)
tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("**/version.properties") { expand("version" to v) }
}
