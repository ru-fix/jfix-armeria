import de.marcphilipp.gradle.nexus.NexusPublishExtension
import org.asciidoctor.gradle.AsciidoctorTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.properties.ReadOnlyProperty

buildscript {

    repositories {
        mavenCentral()
        mavenLocal()
    }

    dependencies {
        classpath(Libs.gradle_release_plugin)
        classpath(Libs.asciidoctor_plugin)
        classpath(kotlin("gradle-plugin", version = Vers.kotlin))
    }
}

plugins {
    kotlin("jvm") version Vers.kotlin apply false
    signing
    `maven-publish`
    id(Libs.old_nexus_publish_plugin_id) version Vers.old_nexus_publish_plugin apply false
    id(Libs.nexus_staging_plugin_id) version Vers.nexus_staging_plugin
    id(Libs.asciidoctor_plugin_id) version Vers.asciidoctor_plugin
    id(Libs.spring_boot_plugin_id) version Vers.spring_boot apply false
    id(Libs.docker_spring_boot_plugin_id) version Vers.docker_plugin apply false
    id(Libs.dokka_plugin_id) version Vers.dokka_plugin
}

/**
 * Project configuration by properties and environment
 */
fun envConfig() = ReadOnlyProperty<Any?, String?> { _, property ->
    if (ext.has(property.name)) {
        ext[property.name] as? String
    } else {
        System.getenv(property.name)
    }
}

val repositoryUser by envConfig()
val repositoryPassword by envConfig()
val repositoryUrl by envConfig()
val signingKeyId by envConfig()
val signingPassword by envConfig()
val signingSecretKeyRingFile by envConfig()

nexusStaging {
    packageGroup = ProjectGroup
    username = "$repositoryUser"
    password = "$repositoryPassword"
    numberOfRetries = 50
    delayBetweenRetriesInMillis = 3_000
}

apply {
    plugin("ru.fix.gradle.release")
}

subprojects {

    group = ProjectGroup

    apply {
        plugin("maven-publish")
        plugin("signing")
        plugin("java")
        plugin(Libs.dokka_plugin_id)
        plugin(Libs.old_nexus_publish_plugin_id)
    }

    repositories {
        mavenCentral()
        mavenLocal()
        if (!repositoryUrl.isNullOrEmpty()) {
            maven(url = repositoryUrl.toString()) {
                isAllowInsecureProtocol = true
            }
        }
    }

    val sourcesJar by tasks.creating(Jar::class) {
        archiveClassifier.set("sources")
        from("src/main/java")
        from("src/main/kotlin")
    }

    val dokkaJavadocJar by tasks.register<Jar>("dokkaJavadocJar") {
        archiveClassifier.set("javadoc")

        from(tasks.dokkaJavadoc.flatMap { it.outputDirectory })
        dependsOn(tasks.dokkaJavadoc)
    }

    val dokkaHtmlJar by tasks.register<Jar>("dokkaHtmlJar") {
        archiveClassifier.set("html-doc")

        from(tasks.dokkaHtml.flatMap { it.outputDirectory })
        dependsOn(tasks.dokkaHtml)
    }

    configure<NexusPublishExtension> {
        repositories {
            sonatype {
                username.set("$repositoryUser")
                password.set("$repositoryPassword")
                useStaging.set(true)
            }
        }
        clientTimeout.set(Duration.of(4, ChronoUnit.MINUTES))
        connectTimeout.set(Duration.of(4, ChronoUnit.MINUTES))
    }

    configurations.all {
        resolutionStrategy {
            force(Libs.kotlin_stdlib, Libs.kotlin_stdlib_common, Libs.kotlin_jdk8, Libs.kotlin_reflect)
            force(Libs.kotlinx_coroutines_core, Libs.kotlinx_coroutines_jdk8)
        }
    }

    project.afterEvaluate {
        if (project.name.startsWith("jfix-armeria")) {
            publishing {

                publications {
                    // Internal repository setup
                    repositories {
                        maven {
                            url = uri("$repositoryUrl")
                            isAllowInsecureProtocol = true
                            if (url.scheme.startsWith("http", true)) {
                                credentials {
                                    username = "$repositoryUser"
                                    password = "$repositoryPassword"
                                }
                            }
                        }
                    }

                    create<MavenPublication>("maven") {
                        from(components["java"])

                        artifact(sourcesJar)
                        artifact(dokkaJavadocJar)
                        artifact(dokkaHtmlJar)

                        pom {
                            name.set("${project.group}:${project.name}")
                            description.set("https://github.com/ru-fix/")
                            url.set("https://github.com/ru-fix/${rootProject.name}")
                            licenses {
                                license {
                                    name.set("The Apache License, Version 2.0")
                                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                                }
                            }
                            developers {
                                developer {
                                    id.set("JFix Team")
                                    name.set("JFix Team")
                                    url.set("https://github.com/ru-fix/")
                                }
                            }
                            scm {
                                url.set("https://github.com/ru-fix/${rootProject.name}")
                                connection.set("https://github.com/ru-fix/${rootProject.name}.git")
                                developerConnection.set("https://github.com/ru-fix/${rootProject.name}.git")
                            }
                        }
                    }
                }
            }
        }
    }

    configure<SigningExtension> {
        if (!signingKeyId.isNullOrEmpty()) {
            project.ext["signing.keyId"] = signingKeyId
            project.ext["signing.password"] = signingPassword
            project.ext["signing.secretKeyRingFile"] = signingSecretKeyRingFile
            logger.info("Signing key id provided. Sign artifacts for $project.")
            isRequired = true
        } else {
            logger.warn("${project.name}: Signing key not provided. Disable signing for  $project.")
            isRequired = false
        }
        sign(publishing.publications)
    }

    tasks {
        withType<JavaCompile> {
            sourceCompatibility = JavaVersion.VERSION_1_8.toString()
            targetCompatibility = JavaVersion.VERSION_1_8.toString()
            options.release.set(8)
        }
        withType<KotlinCompile> {
            kotlinOptions {
                jvmTarget = JavaVersion.VERSION_1_8.toString()
            }
        }
        "test"(Test::class) {
            useJUnitPlatform {
                excludeTags("integration")
            }

            maxParallelForks = 10

            testLogging {
                events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
                showStandardStreams = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
        create<Test>("integrationTest") {
            description = "Runs integration tests."
            group = "verification"

            useJUnitPlatform {
                includeTags("integration")
            }
            testLogging {
                events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
                showStandardStreams = true
                exceptionFormat = TestExceptionFormat.FULL
            }
            shouldRunAfter("test")
        }
    }
}

tasks {
    withType<AsciidoctorTask> {
        sourceDir = project.file("asciidoc")
        resources(
            closureOf<CopySpec> {
                from("asciidoc")
                include("**/*.png")
            }
        )
        doLast {
            copy {
                from(outputDir.resolve("html5"))
                into(project.file("docs"))
                include("**/*.html", "**/*.png")
            }
        }
    }
}
