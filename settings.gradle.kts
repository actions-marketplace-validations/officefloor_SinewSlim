// the Central publisher's plugin is released to Maven Central, not the Gradle Plugin Portal
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "sinew-slim"

// The libraries' projects carry their artifact names, so a project building on them with a
// composite build (includeBuild) substitutes net.officefloor.sinewslim:sinewslim-* without a
// substitution block.
// Their directories keep the short names.
val libraries = mapOf(
    "sinewslim-facts" to "facts",
    "sinewslim-core" to "core",
    "sinewslim-javaparser" to "javaparser",
    "sinewslim-config" to "config",
    "sinewslim-scan" to "scan",
)
for ((name, dir) in libraries) {
    include(name)
    project(":$name").projectDir = file(dir)
}
include("cli", "intellij")
