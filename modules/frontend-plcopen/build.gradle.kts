plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-ast"))
    api(project(":frontend-st"))
    implementation(project(":plc-grammar"))
    implementation(libs.woodstox)
    testImplementation(testFixtures(project(":plc-core")))
    testImplementation(libs.jazzer.junit)
}
