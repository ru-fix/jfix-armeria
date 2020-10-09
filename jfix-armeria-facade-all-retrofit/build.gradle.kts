import JFixArmeriaFacadeFeatures.*

plugins {
    kotlin("jvm")
}

dependencies {
    api(project(Projs.`facade-all`.dependency))
    api(project(Projs.facade.dependency)) {
        capabilities {
            requireCapabilities(*`retrofit with jfix-stdlib executors support`.capabilitiesNotations)
        }
    }

    //Maven compatibility
    api(Libs.armeria_retrofit2)
    implementation(Libs.jfix_stdlib_concurrency)


    testImplementation(Libs.kotlin_jdk8)
    testImplementation(Libs.kotlinx_coroutines_core)
    testImplementation(Libs.kotlinx_coroutines_jdk8)
    //   Junit
    testImplementation(Libs.junit_api)
    testImplementation(Libs.junit_params)
    testRuntimeOnly(Libs.junit_engine)
    testRuntimeOnly(Libs.corounit_engine)
    testImplementation(project(Projs.`commons-testing`.dependency))
}
