package ru.fix.armeria.facade.it

import mu.KotlinLogging
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.concurrent.thread

private val Network = org.testcontainers.containers.Network.newNetwork()

object JFixTestWebfluxServerContainer : GenericContainer<JFixTestWebfluxServerContainer>(
    "jfix-test-webflux-server:latest"
) {
    private const val EXPOSED_PORT = 8080

    private val logger = KotlinLogging.logger { }

    init {
        withExposedPorts(EXPOSED_PORT)
            .withNetwork(Network)
            .withLogConsumer(Slf4jLogConsumer(logger))
            .waitingFor(Wait.forHttp("/").forStatusCode(404))
            .start()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            stop()
        })
    }

    val serverPort: Int
        get() = getMappedPort(EXPOSED_PORT)

}
