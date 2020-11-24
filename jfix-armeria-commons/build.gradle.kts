plugins {
    kotlin("jvm")
    id(Libs.shadow_plugin)
}

dependencies {
    api(Libs.armeria)

    implementation(Libs.kotlin_jdk8)
}
