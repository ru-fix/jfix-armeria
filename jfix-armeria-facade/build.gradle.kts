plugins {
    kotlin("jvm")
}

dependencies {
    // Kotlin
    implementation(Libs.kotlin_stdlib)
    implementation(Libs.kotlin_jdk8)

    api(Libs.armeria)
    implementation(Libs.armeria_retrofit2)

    // jfix-armeria modules
    implementation(project(Projs.`dynamic-request`.dependency))
    implementation(project(Projs.commons.dependency))
    implementation(project(Projs.`aggregating-profiler`.dependency))
    implementation(project(Projs.limiter.dependency))

    // jfix-components. Compile-only in order do not require facade clients to transitively depend on them
    compileOnly(Libs.aggregating_profiler)
    compileOnly(Libs.dynamic_property_api)
    compileOnly(Libs.jfix_stdlib_ratelimiter)

    // Logging
    implementation(Libs.log4j_kotlin)


    // Testing
    testImplementation(project(Projs.`commons-testing`.dependency))
    testImplementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_jdk8)
    //   Junit
    testImplementation(Libs.junit_api)
    testImplementation(Libs.junit_params)
    testRuntimeOnly(Libs.junit_engine)
    testRuntimeOnly(Libs.corounit_engine)
    //  Kotest
    testImplementation(Libs.kotest_assertions_core_jvm)
    testImplementation(Libs.kotest_assertions_json_jvm)
    //  Test Logging
    testRuntimeOnly(Libs.log4j_core)
    testRuntimeOnly(Libs.slf4j_over_log4j)
    //  Mocking
    testImplementation(Libs.mockk)
    testImplementation(Libs.armeria_junit5)
    //  Retrofit integration
    testImplementation(Libs.retrofit2_converter_jackson)
    //  Other useful libs
    testImplementation(Libs.jfix_stdlib_socket)
}
