rootProject.name = "jfix-armeria"

val projects = listOf(
    "commons",
    "commons-testing",
    "dynamic-request",
    "aggregating-profiler",
    "limiter",
    "facade"
).map { "${rootProject.name}-$it" }

for (project in projects) {
    include(project)
}
