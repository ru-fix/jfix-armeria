plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(Projs.commons.dependency))

    api(Libs.aggregating_profiler)
    api(Libs.armeria)

    // Kotlin
    implementation(Libs.kotlin_stdlib)
    implementation(Libs.kotlin_jdk8)

    // Logging
    implementation(Libs.log4j_kotlin)

    // Testing
    //   Junit
    testImplementation(Libs.junit_api)
    testImplementation(Libs.junit_params)
    testRuntimeOnly(Libs.junit_engine)
    //  Kotest
    testImplementation(Libs.kotest_runner_junit5_jvm)
    testImplementation(Libs.kotest_assertions_core_jvm)
    //  Test Logging
    testRuntimeOnly(Libs.log4j_core)
    testRuntimeOnly(Libs.slf4j_over_log4j)
    //  Mocking
    testImplementation(Libs.mockk)
    testImplementation(Libs.armeria_testing_junit)
    //  JFix
    testImplementation(Libs.jfix_stdlib_socket)
}
