plugins {
    id("plca.cli")
}

dependencies {
    implementation(project(":plc-report"))
    implementation(project(":frontend-st"))
    implementation(project(":frontend-scl"))
    implementation(project(":frontend-l5x"))
    implementation(project(":frontend-plcopen"))
}

application {
    applicationName = "plca"
    mainClass = "dev.plca.cli.Main" // added in M3
}
