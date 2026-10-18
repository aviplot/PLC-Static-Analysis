// Root project: formatting for files that do not belong to a Java module.

plugins {
    id("com.diffplug.spotless")
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "modules/*/*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        ktlint()
    }
    format("antlr") {
        target("modules/*/src/**/*.g4")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
