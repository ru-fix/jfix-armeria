rootProject.name = "jfix-armeria"

val projects = listOf(
    "commons",
    "dynamic-request-options",
    "aggregating-profiler"
).map { "${rootProject.name}-$it" }

for (project in projects) {
    include(project)
}
