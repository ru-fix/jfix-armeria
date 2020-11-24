import JFixArmeriaFacadeFeatures.*

plugins {
    kotlin("jvm")
    id(Libs.shadow_plugin)
}

dependencies {
    api(project(Projs.facade.dependency))
    api(project(Projs.facade.dependency)) {
        capabilities {
            requireCapabilities(*`dynamic-request support`.capabilitiesNotations)
        }
    }
    api(project(Projs.facade.dependency)) {
        capabilities {
            requireCapabilities(*`aggregating-profiler support`.capabilitiesNotations)
        }
    }
    api(project(Projs.facade.dependency)) {
        capabilities {
            requireCapabilities(*`rate-limiter support`.capabilitiesNotations)
        }
    }

    //Maven compatibility
    api(project(Projs.`dynamic-request`.dependency))
    api(project(Projs.`aggregating-profiler`.dependency))
    api(project(Projs.limiter.dependency))

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
