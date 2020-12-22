object Vers {

    // Gradle plugins
    const val gradle_release_plugin = "1.3.17"
    const val dokkav_plugin = "0.10.1"
    const val asciidoctor_plugin = "1.5.9.2"
    const val nexus_staging_plugin = "0.21.2"
    const val nexus_publish_plugin = "0.4.0"
    const val docker_plugin = "6.6.1"

    // Kotlin Dependencies
    const val kotlin = "1.3.72"
    const val kotlinx_coroutines = "1.3.8"

    // JFix components
    const val aggregating_profiler = "1.6.6"
    const val dynamic_property = "2.0.8"
    const val jfix_stdlib = "3.0.12"

    // Armeria and Retrofit
    const val armeria = "1.2.0"
    const val retrofit = "2.9.0"

    // Logging
    const val log4j = "2.12.0"
    const val log4j_kotlin = "1.0.0"
    const val kotlin_logging = "2.0.3"

    // Spring
    const val spring_boot = "2.3.4.RELEASE"

    // Testing
    const val junit = "5.7.0"
    const val kotest = "4.3.0"
    const val mockk = "1.10.2"
    const val corounit = "1.0.32"
    const val testcontainers = "1.14.3"
}

object Libs {

    // Gradle plugins
    const val gradle_release_plugin = "ru.fix:gradle-release-plugin:${Vers.gradle_release_plugin}"
    const val dokka_plugin = "org.jetbrains.dokka:dokka-gradle-plugin:${Vers.dokkav_plugin}"
    const val asciidoctor_plugin_id = "org.asciidoctor.convert"
    const val asciidoctor_plugin = "org.asciidoctor:asciidoctor-gradle-plugin:${Vers.asciidoctor_plugin}"
    const val nexus_staging_plugin_id = "io.codearte.nexus-staging"
    const val nexus_publish_plugin_id = "de.marcphilipp.nexus-publish"
    const val spring_boot_plugin_id = "org.springframework.boot"
    const val docker_spring_boot_plugin_id = "com.bmuschko.docker-spring-boot-application"

    // Kotlin
    const val kotlin_stdlib = "org.jetbrains.kotlin:kotlin-stdlib:${Vers.kotlin}"
    const val kotlin_jdk8 = "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Vers.kotlin}"
    const val kotlinx_coroutines_core = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Vers.kotlinx_coroutines}"
    const val kotlinx_coroutines_jdk8 = "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Vers.kotlinx_coroutines}"
    const val kotlinx_coroutines_reactor = "org.jetbrains.kotlinx:kotlinx-coroutines-reactor:${Vers.kotlinx_coroutines}"

    // JFix components
    const val dynamic_property_api = "ru.fix:dynamic-property-api:${Vers.dynamic_property}"
    const val aggregating_profiler = "ru.fix:aggregating-profiler:${Vers.aggregating_profiler}"
    const val jfix_stdlib_socket = "ru.fix:jfix-stdlib-socket:${Vers.jfix_stdlib}"
    const val jfix_stdlib_ratelimiter = "ru.fix:jfix-stdlib-ratelimiter:${Vers.jfix_stdlib}"
    const val jfix_stdlib_concurrency = "ru.fix:jfix-stdlib-concurrency:${Vers.jfix_stdlib}"

    // Logging
    const val log4j_kotlin = "org.apache.logging.log4j:log4j-api-kotlin:${Vers.log4j_kotlin}"
    const val log4j_core = "org.apache.logging.log4j:log4j-core:${Vers.log4j}"
    const val slf4j_over_log4j = "org.apache.logging.log4j:log4j-slf4j-impl:${Vers.log4j}"
    const val kotlin_logging = "io.github.microutils:kotlin-logging:${Vers.kotlin_logging}"

    // Armeria
    const val armeria = "com.linecorp.armeria:armeria:${Vers.armeria}"
    const val armeria_kotlin = "com.linecorp.armeria:armeria-kotlin:${Vers.armeria}"
    const val armeria_retrofit2 = "com.linecorp.armeria:armeria-retrofit2:${Vers.armeria}"

    // Retrofit
    const val retrofit2_converter_jackson = "com.squareup.retrofit2:converter-jackson:${Vers.retrofit}"
    const val retrofit2_converter_scalars = "com.squareup.retrofit2:converter-scalars:${Vers.retrofit}"

    // Spring
    const val spring_boot_starter_webflux = "org.springframework.boot:spring-boot-starter-webflux:${Vers.spring_boot}"

    // Testing
    //  Junit
    const val junit_api = "org.junit.jupiter:junit-jupiter-api:${Vers.junit}"
    const val junit_params = "org.junit.jupiter:junit-jupiter-params:${Vers.junit}"
    const val junit_engine = "org.junit.jupiter:junit-jupiter-engine:${Vers.junit}"
    const val armeria_junit5 = "com.linecorp.armeria:armeria-junit5:${Vers.armeria}"
    //  Kotest
    const val kotest_assertions_core = "io.kotest:kotest-assertions-core:${Vers.kotest}"
    const val kotest_assertions_json = "io.kotest:kotest-assertions-json:${Vers.kotest}"
    //  Corounit
    const val corounit_engine = "ru.fix:corounit-engine:${Vers.corounit}"
    const val mockk = "io.mockk:mockk:${Vers.mockk}"
    //  TestContainers
    const val testcontainers = "org.testcontainers:testcontainers:${Vers.testcontainers}"
}

const val ProjectGroup = "ru.fix"

enum class Projs {

    `aggregating-profiler`,
    commons,
    `commons-testing`,
    `dynamic-request`,
    limiter,
    facade,
    `facade-all`;

    val moduleName get() = "jfix-armeria-$name"
    val dependency get(): String = ":$moduleName"
}

enum class JFixArmeriaFacadeFeatures(
    val featureName: String,
    _capabilities: Set<String>
) {

    `dynamic-request support`(
        "dynamicRequestSupport",
        setOf(
            "dynamic-request-support"
        )
    ),
    `aggregating-profiler support`(
        "aggregatingProfilerSupport",
        setOf(
            "aggregating-profiler-support"
        )
    ),
    `rate-limiter support`(
        "rateLimiterSupport",
        setOf(
            "rate-limiter-support"
        )
    ),
    `retrofit support`(
        "retrofitSupport",
        setOf(
            "retrofit-support"
        )
    ),
    `retrofit with jfix-stdlib executors support`(
        "retrofitWithJfixStdlibExecutorsSupport",
        setOf(
            "retrofit-support",
            "jfix-stdlib-executors-support"
        )
    );

    val capabilitiesNames: List<String> = _capabilities.map { "${Projs.facade.moduleName}-$it" }
    val capabilitiesNotations: Array<String> = capabilitiesNames.map { "$ProjectGroup:$it" }.toTypedArray()

    val api = "${featureName}Api"
    val implementation = "${featureName}Implementation"

}