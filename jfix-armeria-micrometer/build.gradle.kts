plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(Projs.commons.dependency))
    api(Libs.armeria)

    implementation(Libs.kotlin_jdk8)
    implementation(Libs.log4j_kotlin)

    //   Junit
    testImplementation(Libs.junit_api)
    testImplementation(Libs.junit_params)
    testRuntimeOnly(Libs.junit_engine)
    //  Kotest
    testImplementation(Libs.kotest_assertions_core_jvm)
    //  Test Logging
    testImplementation(Libs.log4j_kotlin)
    testRuntimeOnly(Libs.log4j_core)
    testRuntimeOnly(Libs.slf4j_over_log4j)
    //  Testing
    testImplementation(project(Projs.`commons-testing`.dependency))
    testImplementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_jdk8)
}