rootProject.name = "jfix-armeria"

val projects = listOf(
    "commons",
    "commons-testing",
    "dynamic-request",
    "aggregating-profiler",
    "limiter",
    "micrometer",
    "facade",
    "facade-all",
    "facade-all-retrofit"
).map { "${rootProject.name}-$it" }

for (project in projects) {
    include(project)
}
include("jfix-test-webflux-server")
