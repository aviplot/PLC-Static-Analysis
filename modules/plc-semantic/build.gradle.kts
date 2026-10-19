plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-ast"))
    // tests build their ASTs with the real frontend; main code depends on plc-ast only
    testImplementation(project(":frontend-st"))
    testImplementation(project(":frontend-scl"))
    testImplementation(project(":frontend-l5x"))
    testImplementation(testFixtures(project(":plc-core")))
    testImplementation(libs.jazzer.junit)
}
