plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-semantic"))
    // tests build their models with the real frontend; main code depends on plc-semantic/plc-ast only
    testImplementation(project(":frontend-st"))
    testImplementation(project(":frontend-scl"))
    testImplementation(project(":frontend-l5x"))
    testImplementation(project(":frontend-plcopen"))
    testImplementation(testFixtures(project(":plc-core")))
    testImplementation(libs.jazzer.junit)
}
