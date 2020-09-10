package ru.fix.armeria.facade

import org.apache.logging.log4j.kotlin.Logging
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.containers.Network
import ru.fix.stdlib.socket.SocketChecker
import kotlin.concurrent.thread

private const val VERSION = "5.11.1"
private const val PORT = 1080

object MockServerContainer : Logging,
    GenericContainer<ru.fix.armeria.facade.MockServerContainer>("jamesdbloom/mockserver:mockserver-$VERSION") {

    //val container: MockServerContainer = MockServerContainer("5.11.1")

    //private val mockServerNetwork = Network.newNetwork()

    init {
        //val port = SocketChecker.getAvailableRandomPort()
        val port = PORT
        this.withCommand("-logLevel DEBUG -serverPort $port")
            //.withNetwork(mockServerNetwork)
            .withNetworkAliases("mockserver")
            .withExposedPorts(port)
        if (/*container.*/isRunning) {
            logger.warn { "MockServerContainer is already running. Endpoint address: ${/*container.*/endpoint}" }
        }
        start()
        //container.start()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            //container.stop()
            stop()
        })
    }

    val endpoint: String
        get() = String.format("http://%s:%d", host, getMappedPort(MockServerContainer.PORT))

    val serverPort: Int
        get() = getMappedPort(MockServerContainer.PORT)
}