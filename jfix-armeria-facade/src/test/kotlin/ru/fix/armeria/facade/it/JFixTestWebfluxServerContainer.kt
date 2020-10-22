package ru.fix.armeria.facade.it

import mu.KotlinLogging
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.concurrent.thread

private const val PORT = 8080

object JFixTestWebfluxServerContainer : GenericContainer<JFixTestWebfluxServerContainer>(
    "jfix-test-webflux-server:latest"
) {

    private val logger = KotlinLogging.logger { }

    init {
        withExposedPorts(PORT)
            .withLogConsumer(Slf4jLogConsumer(logger))
            .waitingFor(Wait.forHttp("/").forStatusCode(404))
            .start()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            stop()
        })
    }

    val endpoint: String
        get() = String.format("http://%s:%d", host, serverPort)

    val serverPort: Int
        get() = getMappedPort(PORT)

    const val basePath: String = "/jfix/armeria/test-webflux/v1"
}