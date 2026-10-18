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
