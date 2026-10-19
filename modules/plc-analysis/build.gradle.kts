// The analysis pipeline shared by the CLI and the language server: load, model groups, rules, baseline, plca.yaml.
plugins {
    id("plca.java")
}

dependencies {
    api(project(":plc-report"))
    api(project(":frontend-st"))
    implementation(project(":frontend-scl"))
    implementation(project(":frontend-l5x"))
    implementation(project(":frontend-plcopen"))
    implementation(libs.jackson.yaml)
}
