// Command-line application (plc-cli).

plugins {
    id("plca.java")
    application
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("picocli").get())
}
