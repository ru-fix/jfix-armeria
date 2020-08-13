plugins {
    kotlin("jvm")
}

dependencies {
    api(Libs.armeria)
    api(Libs.armeria_junit5)

    implementation(Libs.kotlin_jdk8)
    implementation(Libs.kotlinx_coroutines_jdk8)
}
