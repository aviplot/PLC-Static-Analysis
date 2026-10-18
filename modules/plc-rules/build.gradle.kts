plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-semantic"))
    testImplementation(testFixtures(project(":plc-core")))
}
