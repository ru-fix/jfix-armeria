plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(Projs.commons.dependency))

    api(Libs.armeria)
    api(Libs.dynamic_property_api)

    // Kotlin
    implementation(Libs.kotlin_stdlib)
    implementation(Libs.kotlin_jdk8)

    // Testing
    testImplementation(Libs.junit_api)
    testImplementation(Libs.junit_params)
    testRuntimeOnly(Libs.junit_engine)
    testImplementation(Libs.kotest_assertions_core_jvm)
    testImplementation(Libs.armeria_testing_junit)
    testRuntimeOnly(Libs.log4j_core)
    testRuntimeOnly(Libs.slf4j_over_log4j)
}
