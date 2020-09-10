package ru.fix.armeria.facade

import org.apache.logging.log4j.kotlin.Logging
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.concurrent.thread

private const val VERSION = "5.11.1"
private const val PORT = 1080

object MockServerContainer : Logging,
    GenericContainer<ru.fix.armeria.facade.MockServerContainer>(
        "mockserver/mockserver:mockserver-$VERSION"
    ) {

    //val container: MockServerContainer = MockServerContainer("5.11.1")

    init {
        this.withCommand("-logLevel DEBUG -serverPort $PORT")
            .withExposedPorts(PORT)
            //workaround for https://github.com/testcontainers/testcontainers-java/issues/2984
            .withEnv("MOCKSERVER_LIVENESS_HTTP_GET_PATH", "/health")
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
        //addFixedExposedPort(hostPort, port)
        if (/*container.*/isRunning) {
            logger.warn { "MockServerContainer is already running. Endpoint address: ${/*container.*/endpoint}" }
        }
        start()
        //container.start()

//        Runtime.getRuntime().addShutdownHook(thread(start = false) {
//            //container.stop()
//            stop()
//        })
    }

    val endpoint: String
        get() = String.format("http://%s:%d", host, getMappedPort(MockServerContainer.PORT))

    val serverPort: Int
        get() = getMappedPort(MockServerContainer.PORT)
}