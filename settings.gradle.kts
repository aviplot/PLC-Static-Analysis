pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "plca"

// Dependency direction (design rule 3: rules never see ANTLR parse trees):
//   plc-core <- plc-ast <- plc-semantic <- plc-rules <- plc-report <- plc-analysis <- plc-lsp <- plc-web <- plc-cli
//   plc-grammar <- frontend-* (implementation only) -> plc-ast; frontend-* <- plc-analysis
//   plc-devtools: corpus check, parse debugging, JMH benchmarks (never shipped)
listOf(
    "plc-core",
    "plc-grammar",
    "plc-ast",
    "frontend-st",
    "frontend-scl",
    "frontend-l5x",
    "frontend-plcopen",
    "plc-semantic",
    "plc-rules",
    "plc-report",
    "plc-analysis",
    "plc-lsp",
    "plc-web",
    "plc-cli",
    "plc-devtools",
).forEach { name ->
    include(name)
    project(":$name").projectDir = file("modules/$name")
}

// VS Code extension (M8): npm-driven, not part of `build` (Node is needed only for these tasks).
include("editors:vscode")
