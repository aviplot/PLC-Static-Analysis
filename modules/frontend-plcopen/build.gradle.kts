plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-ast"))
    implementation(project(":plc-grammar"))
    testImplementation(testFixtures(project(":plc-core")))
}
