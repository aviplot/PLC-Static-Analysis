// Conventions shared by every Java module: toolchain, compiler flags, tests, formatting, Checkstyle.

plugins {
    `java-library`
    checkstyle
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun version(alias: String): String = libs.findVersion(alias).get().requiredVersion

group = "dev.plca"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(version("java")) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = version("java").toInt()
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(version("junit"))
            dependencies {
                implementation(libs.findLibrary("assertj-core").get())
            }
        }
    }
}

// Code is compiled for Java 21; `-Pplca.testJdk=25` runs the same tests on a newer runtime.
val testJdk = providers.gradleProperty("plca.testJdk")
val goldenUpdate = providers.gradleProperty("golden.update").map { it != "false" }.orElse(false)

tasks.withType<Test>().configureEach {
    if (testJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(testJdk.get()) }
    }
    systemProperty(
        "golden.root",
        layout.projectDirectory
            .dir("src/test/resources")
            .asFile.absolutePath,
    )
    systemProperty("golden.update", goldenUpdate.get().toString())
    if (goldenUpdate.get()) outputs.upToDateWhen { false }
}

spotless {
    java {
        palantirJavaFormat(version("palantir-java-format")).formatJavadoc(true)
        trimTrailingWhitespace()
        endWithNewline()
        // only our own sources: the default target also picks up generated code and, in the ANTLR module,
        // paths under build/ that compileJava writes concurrently (flaky "Could not read path" failures)
        target("src/*/java/**/*.java")
    }
}

checkstyle {
    toolVersion = version("checkstyle")
    configDirectory = rootProject.layout.projectDirectory.dir("config/checkstyle")
    maxWarnings = 0
}
