import JFixArmeriaFacadeFeatures.*

plugins {
    java
    kotlin("jvm")
}
java {
    val main by sourceSets
    for (facadeFeature in JFixArmeriaFacadeFeatures.values()) {
        registerFeature(facadeFeature.featureName) {
            usingSourceSet(main)
            for (capabilityName in facadeFeature.capabilitiesNames) {
                capability(group.toString(), capabilityName, version.toString())
            }
        }
    }
}


dependencies {
    // Kotlin
    implementation(Libs.kotlin_stdlib)
    implementation(Libs.kotlin_jdk8)

    api(Libs.armeria)


    // jfix-armeria optional modules
    implementation(project(Projs.commons.dependency))

    `dynamic-request support`.api(project(Projs.`dynamic-request`.dependency))
    `dynamic-request support`.api(Libs.dynamic_property_api)

    `aggregating-profiler support`.implementation(project(Projs.`aggregating-profiler`.dependency))
    `aggregating-profiler support`.api(Libs.aggregating_profiler)

    `rate-limiter support`.implementation(project(Projs.limiter.dependency))
    `rate-limiter support`.api(Libs.jfix_stdlib_ratelimiter)

    `retrofit support`.api(Libs.armeria_retrofit2)

    `retrofit with jfix-stdlib executors support`.api(Libs.armeria_retrofit2)
    `retrofit with jfix-stdlib executors support`.implementation(Libs.jfix_stdlib_concurrency)


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
    testImplementation(Libs.armeria_kotlin)
    testImplementation(Libs.mockserver_client_java)
    //  Retrofit integration
    testImplementation(Libs.retrofit2_converter_jackson)
    testImplementation(Libs.retrofit2_converter_scalars)
    //  TestContainers
    testImplementation(Libs.testcontainers)
    //testImplementation(Libs.testcontainers_junit_jupiter)
    testImplementation(Libs.testcontainers_mockserver)
    //  Other useful libs
    testImplementation(Libs.jfix_stdlib_socket)
}
