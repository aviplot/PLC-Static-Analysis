plugins {
    id("plca.java")
    `java-test-fixtures`
}

dependencies {
    testFixturesImplementation(libs.assertj.core)
}
