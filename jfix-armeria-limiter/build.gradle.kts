plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(Projs.commons.dependency))

    api(Libs.armeria)
    api(Libs.aggregating_profiler)
    api(Libs.dynamic_property_api)

    implementation(Libs.kotlin_jdk8)

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
}
