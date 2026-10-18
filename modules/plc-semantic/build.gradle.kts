plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-ast"))
    // tests build their ASTs with the real frontend; main code depends on plc-ast only
    testImplementation(project(":frontend-st"))
    testImplementation(testFixtures(project(":plc-core")))
    testImplementation(libs.jazzer.junit)
}
