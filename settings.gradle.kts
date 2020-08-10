rootProject.name = "jfix-armeria"

val projects = listOf(
    "commons",
    "dynamic-request",
    "aggregating-profiler",
    "limiter"
).map { "${rootProject.name}-$it" }

for (project in projects) {
    include(project)
}
