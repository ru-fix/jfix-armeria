object Vers {

    // Gradle plugins
    const val gradle_release_plugin = "1.3.17"
    const val dokkav_plugin = "0.10.1"
    const val asciidoctor_plugin = "1.5.9.2"
    const val nexus_staging_plugin = "0.21.2"
    const val nexus_publish_plugin = "0.4.0"

    // Kotlin Dependencies
    const val kotlin = "1.3.72"

    // JFix components
    const val aggregating_profiler = "1.6.5"
    const val dynamic_property = "2.0.7"
    const val jfix_stdlib = "3.0.8"

    // Armeria
    const val armeria = "0.99.6"

    // Logging
    const val log4j = "2.12.0"
    const val log4j_kotlin = "1.0.0"

    // Testing
    const val junit = "5.6.2"
    const val kotest = "4.1.1"
    const val mockk = "1.10.0"
}

object Libs {

    // Gradle plugins
    const val gradle_release_plugin = "ru.fix:gradle-release-plugin:${Vers.gradle_release_plugin}"
    const val dokka_plugin = "org.jetbrains.dokka:dokka-gradle-plugin:${Vers.dokkav_plugin}"
    const val asciidoctor_plugin_id = "org.asciidoctor.convert"
    const val asciidoctor_plugin = "org.asciidoctor:asciidoctor-gradle-plugin:${Vers.asciidoctor_plugin}"
    const val nexus_staging_plugin_id = "io.codearte.nexus-staging"
    const val nexus_publish_plugin_id = "de.marcphilipp.nexus-publish"

    // Kotlin
    const val kotlin_stdlib = "org.jetbrains.kotlin:kotlin-stdlib:${Vers.kotlin}"
    const val kotlin_jdk8 = "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Vers.kotlin}"

    // JFix components
    const val dynamic_property_api = "ru.fix:dynamic-property-api:${Vers.dynamic_property}"
    const val aggregating_profiler = "ru.fix:aggregating-profiler:${Vers.aggregating_profiler}"
    const val jfix_stdlib_socket = "ru.fix:jfix-stdlib-socket:${Vers.jfix_stdlib}"

    // Logging
    const val log4j_kotlin = "org.apache.logging.log4j:log4j-api-kotlin:${Vers.log4j_kotlin}"
    const val log4j_core = "org.apache.logging.log4j:log4j-core:${Vers.log4j}"
    const val slf4j_over_log4j = "org.apache.logging.log4j:log4j-slf4j-impl:${Vers.log4j}"

    // Armeria
    const val armeria = "com.linecorp.armeria:armeria:${Vers.armeria}"

    // Testing
    const val junit_api = "org.junit.jupiter:junit-jupiter-api:${Vers.junit}"
    const val junit_params = "org.junit.jupiter:junit-jupiter-params:${Vers.junit}"
    const val junit_engine = "org.junit.jupiter:junit-jupiter-engine:${Vers.junit}"
    const val armeria_testing_junit = "com.linecorp.armeria:armeria-testing-junit:${Vers.armeria}"
    const val kotest_assertions_core_jvm = "io.kotest:kotest-assertions-core-jvm:${Vers.kotest}"
    const val mockk = "io.mockk:mockk:${Vers.mockk}"
}

enum class Projs {

    `commons`,
    `dynamic-request-options`,
    `aggregating-profiler`;

    val dependency get(): String = ":jfix-armeria-$name"
}