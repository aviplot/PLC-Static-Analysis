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
    // differential test of the PLCA-L condition reasoner against an SMT solver (test scope only)
    testImplementation(libs.java.smt)
    testRuntimeOnly(libs.smtinterpol)
}

// longer differential runs: ./gradlew :plc-rules:test --tests '*DifferentialTest*' -Plogic.iterations=1000000 -Plogic.seed=7
tasks.test {
    for (key in listOf("logic.iterations", "logic.seed")) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
