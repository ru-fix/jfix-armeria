plugins {
    java
    kotlin("jvm")
    id("org.springframework.boot")
}

version = ""

dependencies {
    implementation(Libs.kotlin_stdlib)
    implementation(Libs.kotlin_jdk8)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.kotlinx_coroutines_jdk8)
    implementation(Libs.kotlinx_coroutines_reactor)

    implementation(Libs.log4j_kotlin)
    runtimeOnly(Libs.slf4j_over_log4j)

    implementation(Libs.spring_boot_starter_webflux) {
        exclude("org.springframework.boot", "spring-boot-starter-logging")
    }
}
