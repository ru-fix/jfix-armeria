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
    const val dynamic_property = "2.0.5"
    const val jfix_stdlib = "3.0.2"
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
    const val kotlin_reflect = "org.jetbrains.kotlin:kotlin-reflect:${Vers.kotlin}"

}

enum class Projs {

    ;
    val dependency get(): String = ":$name"
}