plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(Projs.commons.dependency))

    api(Libs.armeria)
    api(Libs.jfix_stdlib_ratelimiter)

    implementation(Libs.kotlin_jdk8)

    // Testing
    testImplementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_jdk8)
    //   Junit
    testImplementation(Libs.junit_api)
    testImplementation(Libs.junit_params)
    testRuntimeOnly(Libs.junit_engine)
    testRuntimeOnly(Libs.corounit_engine)
    //  Kotest
    testImplementation(Libs.kotest_assertions_core_jvm)
    //  Test Logging
    testRuntimeOnly(Libs.log4j_core)
    testRuntimeOnly(Libs.slf4j_over_log4j)
    //  Mocking
    testImplementation(Libs.mockk)
    testImplementation(Libs.armeria_testing_junit)
    //  JFix components
    testImplementation(Libs.jfix_stdlib_concurrency)
    //  Armeria
    testImplementation(Libs.armeria_retrofit2)
    //  Other useful testing APIs
    testImplementation(Libs.awailability_kotlin)
}
