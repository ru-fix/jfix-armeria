package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ClientOptions
import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.WebClientBuilder
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.client.retry.RetryingClient
import com.linecorp.armeria.common.*
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.timing.eventually
import io.kotest.inspectors.forOne
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.EPOLL_SOCKET_CHANNEL
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.profiledCallReportWithNameEnding
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.profiledCallReportsWithNameEnding
import ru.fix.armeria.commons.On503AndUnprocessedRetryRule
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.commons.testing.LocalHost
import ru.fix.stdlib.socket.SocketChecker
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
internal class ProfiledHttpClientTest {

    @ParameterizedTest
    @ArgumentsSource(ProtocolSpecificTest.CaseArgumentsProvider::class)
    fun `success request profiled with connect, connected and whole request flow metrics`(
        testCaseArguments: ProtocolSpecificTest.CaseArguments
    ) = runBlocking<Unit> {
        val pathDelayedOk = "/ok-delayed/{delay}"
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        val mockServer = ArmeriaMockServer {
            service(pathDelayedOk) { ctx, _ ->
                val delayMs = ctx.pathParam("delay")!!.toLong()
                HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(delayMs))
            }
        }
        mockServer.start()
        try {
            val mockServerUri = mockServer.uri(SessionProtocol.find(testCaseArguments.clientProtocol)!!)
            val client = WebClient
                .builder(mockServerUri)
                .decorator(ProfiledHttpClient.newDecorator(profiler))
                .build()
            val path = pathDelayedOk.replace("{delay}", 1_000.toString())
            val expectedConnectMetricTags = mapOf(
                MetricTags.METHOD to "GET",
                MetricTags.PATH to path,
                MetricTags.PROTOCOL to testCaseArguments.connectMetricTagProtocol,
                MetricTags.IS_MULTIPLEX_PROTOCOL to testCaseArguments.connectMetricTagIsMultiplex.toString(),
                MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                MetricTags.REMOTE_PORT to mockServer.httpPort().toString()
            )
            val expectedConnectedMetricTags = expectedConnectMetricTags + mapOf(
                //protocol and channel information are determined on this phase
                MetricTags.PROTOCOL to testCaseArguments.otherMetricsTagProtocol,
                MetricTags.IS_MULTIPLEX_PROTOCOL to testCaseArguments.otherMetricsTagIsMultiplex.toString(),
                MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL
            )
            val expectedSuccessMetricTags = expectedConnectedMetricTags + (MetricTags.RESPONSE_STATUS to "200")

            client.get(path).aggregate().await()

            eventually(1.seconds) {
                assertSoftly(profilerReporter.buildReportAndReset()) {
                    profiledCallReportWithNameEnding(Metrics.HTTP_CONNECT) should {
                        it.shouldNotBeNull()

                        it.identity.tags shouldContainExactly expectedConnectMetricTags
                    }
                    profiledCallReportWithNameEnding(Metrics.HTTP_CONNECTED) should {
                        it.shouldNotBeNull()

                        it.identity.tags shouldContainExactly expectedConnectedMetricTags
                    }
                    profiledCallReportWithNameEnding(Metrics.HTTP_SUCCESS) should {
                        it.shouldNotBeNull()

                        it.identity.tags shouldContainExactly expectedSuccessMetricTags
                    }
                }
            }
        } finally {
            mockServer.stop()
        }
    }


    @TestFactory
    fun `http (and not only) error WHEN error occured THEN it is profiled with corresponding status`() =
        DynamicTest.stream(

            listOf(

                ErrorProfilingTest.Case(
                    testCaseName = "Invalid (non 2xx) status 404",
                    mockServerGenerator = {
                        val mockServer = ArmeriaMockServer {
                            service("/some-other-path") { _, _ -> HttpResponse.of(HttpStatus.OK) }
                        }
                        mockServer.start()
                        ErrorProfilingTest.Case.MockServer(mockServer.httpUri()) {
                            mockServer.stop()
                        }
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.ERROR_TYPE to "invalid_status",
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to it.port.toString(),
                            MetricTags.PATH to ErrorProfilingTest.TEST_PATH,
                            MetricTags.METHOD to "GET",
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.RESPONSE_STATUS to "404",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to "true"
                        )
                    },
                    latencyMetricRequired = true
                ),

                ErrorProfilingTest.Case(
                    testCaseName = "Invalid (non 2xx) status 500",
                    mockServerGenerator = {
                        val mockServer = ArmeriaMockServer {
                            service(ErrorProfilingTest.TEST_PATH) { _, _ ->
                                HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
                            }

                        }
                        mockServer.start()
                        ErrorProfilingTest.Case.MockServer(mockServer.httpUri()) {
                            mockServer.stop()
                        }
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.ERROR_TYPE to "invalid_status",
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to it.port.toString(),
                            MetricTags.PATH to ErrorProfilingTest.TEST_PATH,
                            MetricTags.METHOD to "GET",
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.RESPONSE_STATUS to "500",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to "true"
                        )
                    },
                    latencyMetricRequired = true
                ),

                ErrorProfilingTest.Case(
                    testCaseName = "Connection refused",
                    mockServerGenerator = {
                        //non-existing address
                        ErrorProfilingTest.Case.MockServer(
                            URI.create("http://${LocalHost.HOSTNAME.value}:${getAvailableRandomPort()}")
                        ) {}
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.ERROR_TYPE to "connect_refused",
                            MetricTags.PATH to ErrorProfilingTest.TEST_PATH,
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "http",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString(),
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to it.port.toString()
                        )
                    },
                    latencyMetricRequired = true
                ),

                ErrorProfilingTest.Case(
                    testCaseName = "Response timeout",
                    mockServerGenerator = {
                        val mockServer = ArmeriaMockServer {
                            service(ErrorProfilingTest.TEST_PATH) { _, _ ->
                                HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofSeconds(2))
                            }
                        }
                        mockServer.start()

                        ErrorProfilingTest.Case.MockServer(mockServer.httpUri()) {
                            mockServer.stop()
                        }
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to it.port.toString(),
                            MetricTags.ERROR_TYPE to "response_timeout",
                            MetricTags.PATH to ErrorProfilingTest.TEST_PATH,
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to "true",
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL
                        )
                    },
                    clientBuilderCustomizer = {
                        option(ClientOptions.RESPONSE_TIMEOUT_MILLIS, 500)
                    }
                ),

                *listOf(
                    ErrorProfilingTest.ProtocolSpecificCase(
                        protocol = SessionProtocol.H1C,
                        caseName = """Session closed of "http/1 - cleartext" request due to server side response timeout""",
                        profiledErrorType = "response_closed_session"
                    ),
                    ErrorProfilingTest.ProtocolSpecificCase(
                        protocol = SessionProtocol.H2C,
                        caseName = """Stream closed of "http/2 - cleartext" request due to server side response timeout""",
                        profiledErrorType = "response_closed_stream"
                    )
                ).map { protocolSpecificCase ->
                    ErrorProfilingTest.Case(
                        testCaseName = protocolSpecificCase.caseName,
                        mockServerGenerator = {
                            val server = ErrorProfilingTest.Utils.delayedResponsePartsMock(
                                targetPath = ErrorProfilingTest.TEST_PATH,
                                serverRequestTimeout = Duration.ofSeconds(1),
                                fullResponseDelay = Duration.ofSeconds(2),
                                responseStatus = HttpStatus.OK,
                                numberOfResponseParts = 10
                            ).apply {
                                start()
                            }
                            ErrorProfilingTest.Case.MockServer(server.uri(protocolSpecificCase.protocol)) {
                                server.stop()
                            }
                        },
                        expectedErrorMetricTagsByMockUriGenerator = {
                            listOf(
                                MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                                MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                                MetricTags.REMOTE_PORT to it.port.toString(),
                                MetricTags.ERROR_TYPE to protocolSpecificCase.profiledErrorType,
                                MetricTags.PATH to ErrorProfilingTest.TEST_PATH,
                                MetricTags.METHOD to "GET",
                                MetricTags.PROTOCOL to protocolSpecificCase.protocol.uriText(),
                                MetricTags.IS_MULTIPLEX_PROTOCOL to protocolSpecificCase.protocol.isMultiplex.toString(),
                                MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                                MetricTags.RESPONSE_STATUS to "200"
                            )
                        }
                    )
                }.toTypedArray()

            ).iterator(),

            { it.testCaseName }
        ) { (_, setupMockForError: suspend () -> ErrorProfilingTest.Case.MockServer,
                                          getExpectedErrorMetricTags: (URI) -> List<MetricTag>,
                                          latencyMetricRequired: Boolean,
                                          webClientBuilderCustomizer: WebClientBuilder.() -> WebClientBuilder) ->
            runBlocking<Unit> {

                val profiler = AggregatingProfiler()
                val profilerReporter = profiler.createReporter()
                val mockServer = setupMockForError()
                val client = WebClient.builder(mockServer.mockUri)
                    .decorator(ProfiledHttpClient.newDecorator(profiler))
                    .webClientBuilderCustomizer()
                    .build()
                val expectedErrorMetricTags = getExpectedErrorMetricTags(mockServer.mockUri).toMap()

                try {
                    client.get(ErrorProfilingTest.TEST_PATH).aggregate().await()
                } catch (e: Exception) {
                    logger.error(e)
                }

                eventually(1.seconds) {
                    assertSoftly(profilerReporter.buildReportAndReset()) {
                        profiledCallReportWithNameEnding(Metrics.HTTP_ERROR) should {
                            it.shouldNotBeNull()

                            it.identity.tags shouldContainExactly expectedErrorMetricTags
                            if (latencyMetricRequired) {
                                it.latencyMax shouldBeGreaterThan 0
                            } else {
                                it.latencyMax shouldBe 0
                            }
                        }
                    }
                }
            }
        }

    @Test
    suspend fun `http (and not only) error WHEN connection unsuccessful THEN connected metric is not occured`() {
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        val client = WebClient
            .builder(SessionProtocol.HTTP, Endpoint.of(LocalHost.HOSTNAME.value, getAvailableRandomPort()))
            .decorator(ProfiledHttpClient.newDecorator(profiler))
            .build()

        try {
            client.get("/").aggregate().await()
        } catch (e: Exception) {
            logger.error(e)
        }

        eventually(1.seconds) {
            assertSoftly(profilerReporter.buildReportAndReset()) {
                profiledCallReportWithNameEnding(Metrics.HTTP_CONNECT) shouldNotBe null
                profiledCallReportWithNameEnding(Metrics.HTTP_CONNECTED) shouldBe null
                profiledCallReportWithNameEnding(Metrics.HTTP_ERROR) shouldNotBe null
            }
        }
    }

    @Test
    suspend fun `http (and not only) error WHEN endpoint group is empty THEN error profiled`() {
        val testPath = "/test-no-endpoint-group-error"
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        val client = WebClient.builder(SessionProtocol.HTTP, EndpointGroup.of())
            .decorator(ProfiledHttpClient.newDecorator(profiler))
            .build()

        try {
            client.get(testPath).aggregate().await()
        } catch (e: Exception) {
            logger.error(e)
        }

        eventually(1.seconds) {
            assertSoftly(profilerReporter.buildReportAndReset()) {
                profiledCallReportWithNameEnding(Metrics.HTTP_CONNECT) should {
                    it.shouldNotBeNull()

                    it.stopSum shouldBe 1
                }
                profiledCallReportWithNameEnding(Metrics.HTTP_CONNECTED) shouldBe null
                profiledCallReportWithNameEnding(Metrics.HTTP_ERROR) should {
                    it.shouldNotBeNull()

                    it.latencyMax shouldBe 0
                    it.identity.tags shouldContainExactly mapOf(
                        MetricTags.ERROR_TYPE to "no_available_endpoint",
                        MetricTags.PATH to testPath,
                        MetricTags.METHOD to "GET",
                        MetricTags.PROTOCOL to "http",
                        MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString()
                    )
                }
            }
        }
    }

    @Test
    suspend fun `WHEN retry decorator placed before profiled one THEN each retry attempt profiled`() {
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        val mockServer = ArmeriaMockServer().start()
        try {
            mockServer.enqueue {
                HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                    Duration.ofMillis(500)
                )
            }
            mockServer.enqueue {
                HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(200))
            }
            val nonExistingServerPort = getAvailableRandomPort()
            val client = WebClient
                .builder(
                    SessionProtocol.HTTP,
                    EndpointGroup.of(
                        EndpointSelectionStrategy.roundRobin(),
                        mockServer.httpEndpoint(),
                        Endpoint.of(LocalHost.HOSTNAME.value, nonExistingServerPort)
                    )
                )
                .decorator(ProfiledHttpClient.newDecorator(profiler))
                .decorator(RetryingClient.newDecorator(On503AndUnprocessedRetryRule, 3))
                .build()

            client.get("/").aggregate().await()

            eventually(1.seconds) {
                profilerReporter.buildReportAndReset { metric, _ -> metric.name == Metrics.HTTP_CONNECT } should { report ->
                    logger.trace { "Report: $report" }
                    report.profiledCallReportsWithNameEnding(Metrics.HTTP_CONNECT) should { callReports ->
                        callReports.shouldHaveSize(2)

                        callReports.forOne {
                            it.stopSum shouldBe 1
                            it.identity.tags shouldContainExactly mapOf(
                                MetricTags.PATH to "/",
                                MetricTags.METHOD to "GET",
                                MetricTags.PROTOCOL to "http",
                                MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString(),
                                MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                                MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                                MetricTags.REMOTE_PORT to nonExistingServerPort.toString()
                            )
                        }
                        callReports.forOne {
                            it.stopSum shouldBe 2
                            it.identity.tags shouldContainExactly mapOf(
                                MetricTags.PATH to "/",
                                MetricTags.METHOD to "GET",
                                MetricTags.PROTOCOL to "http",
                                MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString(),
                                MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                                MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                                MetricTags.REMOTE_PORT to mockServer.httpPort().toString()
                            )
                        }
                    }
                }
            }
            eventually(1.seconds) {
                profilerReporter.buildReportAndReset { metric, _ -> metric.name == Metrics.HTTP_CONNECTED } should { report ->
                    logger.trace { "Report: $report" }
                    report.profiledCallReportWithNameEnding(Metrics.HTTP_CONNECTED) should {
                        it.shouldNotBeNull()
                        assertSoftly {
                            it.stopSum shouldBe 2
                            it.identity.tags shouldContainExactly mapOf(
                                MetricTags.PATH to "/",
                                MetricTags.METHOD to "GET",
                                MetricTags.PROTOCOL to "h2c",
                                MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                                MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                                MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                                MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                                MetricTags.REMOTE_PORT to mockServer.httpPort().toString()
                            )
                        }
                    }
                }
            }
            eventually(1.seconds) {
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name == Metrics.HTTP_ERROR
                            && metric.tags[MetricTags.ERROR_TYPE]?.let { it == "connect_refused" } ?: false
                }
                logger.trace { "Report: $report" }
                report.profiledCallReportWithNameEnding(Metrics.HTTP_ERROR) should {
                    it.shouldNotBeNull()

                    it.stopSum shouldBe 1
                    it.identity.tags shouldContainExactly mapOf(
                        MetricTags.PATH to "/",
                        MetricTags.METHOD to "GET",
                        MetricTags.PROTOCOL to "http",
                        MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString(),
                        MetricTags.ERROR_TYPE to "connect_refused",
                        MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                        MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                        MetricTags.REMOTE_PORT to nonExistingServerPort.toString()
                    )
                }
            }
            eventually(1.seconds) {
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name == Metrics.HTTP_ERROR
                            && metric.tags[MetricTags.ERROR_TYPE]?.let { it == "invalid_status" } ?: false
                }
                logger.trace { "Report: $report" }
                report.profiledCallReportWithNameEnding(Metrics.HTTP_ERROR) should {
                    it.shouldNotBeNull()

                    it.stopSum shouldBe 1
                    it.identity.tags shouldContainExactly mapOf(
                        MetricTags.PATH to "/",
                        MetricTags.METHOD to "GET",
                        MetricTags.PROTOCOL to "h2c",
                        MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                        MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                        MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                        MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                        MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                        MetricTags.ERROR_TYPE to "invalid_status",
                        MetricTags.RESPONSE_STATUS to "503"
                    )
                }
            }
            eventually(1.seconds) {
                profilerReporter.buildReportAndReset { metric, _ -> metric.name == Metrics.HTTP_SUCCESS } should { report ->
                    logger.trace { "Report: $report" }
                    report.profiledCallReportWithNameEnding(Metrics.HTTP_SUCCESS) should {
                        it.shouldNotBeNull()

                        it.stopSum shouldBe 1
                        it.identity.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                            MetricTags.RESPONSE_STATUS to "200"
                        )
                    }
                }
            }
        } finally {
            mockServer.stop()
        }
    }

    @Test
    suspend fun `WHEN retry decorator placed after profiled one AND attempts are not exceeded THEN all retries profiled as one success request`() {
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        val firstRequestDelayMillis: Long = 500
        val lastRequestDelayMillis: Long = 200
        val (mockServer, mockServer2) = ArmeriaMockServer() to ArmeriaMockServer()
        try {
            listOf(
                mockServer.launchStart(),
                mockServer2.launchStart()
            ).joinAll()
            mockServer.enqueue {
                HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                    Duration.ofMillis(firstRequestDelayMillis)
                )
            }
            mockServer2.enqueue {
                HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.OK), Duration.ofMillis(lastRequestDelayMillis)
                )
            }
            val nonExistingServerPort = getAvailableRandomPort()
            val client = WebClient
                .builder(
                    SessionProtocol.HTTP,
                    EndpointGroup.of(
                        EndpointSelectionStrategy.roundRobin(),
                        mockServer.httpEndpoint(),
                        Endpoint.of(LocalHost.HOSTNAME.value, nonExistingServerPort),
                        mockServer2.httpEndpoint()
                    )
                )
                .decorator(RetryingClient.newDecorator(On503AndUnprocessedRetryRule, 3))
                .decorator(ProfiledHttpClient.newDecorator(profiler))
                .build()
            /*
            TODO
             due to details of context logs when retries are present (see ProfiledHttpClient implementation),
             first endpoint's data is written to metrics. It would be better to fix it or reorganize structure of
             metrics for whole request flow processing.
             */
            val expectedProfiledMockServer = mockServer/*2*/

            client.get("/").aggregate().await()

            eventually(1.seconds) {
                assertSoftly(profilerReporter.buildReportAndReset()) {
                    logger.trace { "Report: $this" }
                    profiledCallReportWithNameEnding(Metrics.HTTP_CONNECT) should {
                        it.shouldNotBeNull()

                        it.stopSum shouldBe 1
                        it.identity.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "http",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString(),
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString()
                        )
                    }
                    profiledCallReportWithNameEnding(Metrics.HTTP_CONNECTED) should {
                        it.shouldNotBeNull()

                        it.stopSum shouldBe 1
                        it.identity.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString()
                        )
                    }
                    profiledCallReportWithNameEnding(Metrics.HTTP_SUCCESS) should {
                        it.shouldNotBeNull()

                        it.stopSum shouldBe 1
                        it.identity.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString(),
                            MetricTags.RESPONSE_STATUS to "200"
                        )
                        it.latencyMax shouldBeGreaterThan firstRequestDelayMillis + lastRequestDelayMillis
                    }
                }
            }

        } finally {
            listOf(
                mockServer.launchStop(),
                mockServer2.launchStop()
            ).joinAll()
        }
    }

    @Test
    suspend fun `WHEN retry decorator placed after profiled one AND attempts are exceeded THEN all retries profiled as one error request`() {
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        val firstRequestDelayMillis: Long = 400
        val lastRequestDelayMillis: Long = 300
        val (mockServer, mockServer2) = ArmeriaMockServer() to ArmeriaMockServer()
        try {
            listOf(
                mockServer.launchStart(),
                mockServer2.launchStart()
            ).joinAll()
            mockServer.enqueue {
                HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                    Duration.ofMillis(firstRequestDelayMillis)
                )
            }
            mockServer2.enqueue {
                HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                    Duration.ofMillis(lastRequestDelayMillis)
                )
            }
            val nonExistingServerPort = getAvailableRandomPort()
            val client = WebClient
                .builder(
                    SessionProtocol.HTTP,
                    EndpointGroup.of(
                        EndpointSelectionStrategy.roundRobin(),
                        mockServer.httpEndpoint(),
                        Endpoint.of(LocalHost.HOSTNAME.value, nonExistingServerPort),
                        mockServer2.httpEndpoint()
                    )
                )
                .decorator(RetryingClient.newDecorator(On503AndUnprocessedRetryRule, 3))
                .decorator(ProfiledHttpClient.newDecorator(profiler))
                .build()
            /*
            TODO
             due to details of context logs when retries are present (see ProfiledHttpClient implementation),
             first endpoint's data is written to metrics. It would be better to fix it or reorganize structure of
             metrics for whole request flow processing.
             */
            val expectedProfiledMockServer = mockServer/*2*/

            client.get("/").aggregate().await()

            eventually(1.seconds) {
                val report = profilerReporter.buildReportAndReset { metric, _ -> metric.name == Metrics.HTTP_CONNECT }
                report.profiledCallReportWithNameEnding(Metrics.HTTP_CONNECT) should {
                    it.shouldNotBeNull()

                    it.stopSum shouldBe 1
                    it.identity.tags shouldContainExactly mapOf(
                        MetricTags.PATH to "/",
                        MetricTags.METHOD to "GET",
                        MetricTags.PROTOCOL to "http",
                        MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString(),
                        MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                        MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                        MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString()
                    )
                }
            }
            eventually(1.seconds) {
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name == Metrics.HTTP_CONNECTED
                }
                report.profiledCallReportWithNameEnding(Metrics.HTTP_CONNECTED) should {
                    it.shouldNotBeNull()

                    it.stopSum shouldBe 1
                    it.identity.tags shouldContainExactly mapOf(
                        MetricTags.PATH to "/",
                        MetricTags.METHOD to "GET",
                        MetricTags.PROTOCOL to "h2c",
                        MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                        MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                        MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                        MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                        MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString()
                    )
                }
            }
            eventually(1.seconds) {
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name == Metrics.HTTP_ERROR
                }
                report.profiledCallReportWithNameEnding(Metrics.HTTP_ERROR) should {
                    it.shouldNotBeNull()

                    it.stopSum shouldBe 1
                    it.identity.tags shouldContainExactly mapOf(
                        MetricTags.PATH to "/",
                        MetricTags.METHOD to "GET",
                        MetricTags.PROTOCOL to "h2c",
                        MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                        MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                        MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                        MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                        MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString(),
                        MetricTags.ERROR_TYPE to "invalid_status",
                        MetricTags.RESPONSE_STATUS to "503"
                    )
                    it.latencyMax shouldBeGreaterThan firstRequestDelayMillis + lastRequestDelayMillis
                }
            }
        } finally {
            listOf(
                mockServer.launchStop(),
                mockServer2.launchStop()
            ).joinAll()
        }
    }

/*
    TODO implement connect timeout case using some network tool, e.g. https://github.com/alexei-led/pumba

    Motivation why it is not implemented immediately -
    it is quite hard to simulate connection timeout in pure java mock server.
    As it said here https://github.com/tomakehurst/wiremock/issues/591:
    > It's basically impossible to reliably force connection timeouts in pure Java at the moment.
    > My recommendation at the moment would be to use a tool that works at the level of the network stack.
 */
//    @Test
//    fun `WHEN connect timeouted THEN error profiled`() {
//    }

    companion object : Logging {

        fun getAvailableRandomPort() = SocketChecker.getAvailableRandomPort()

    }

    object ProtocolSpecificTest {

        data class CaseArguments(
            val clientProtocol: String,
            val connectMetricTagProtocol: String,
            val connectMetricTagIsMultiplex: Boolean,
            val otherMetricsTagProtocol: String,
            val otherMetricsTagIsMultiplex: Boolean
        ) : Arguments {
            override fun get(): Array<Any> = arrayOf(this)
        }

        class CaseArgumentsProvider : ArgumentsProvider {
            override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> = Stream.of(
                CaseArguments(
                    clientProtocol = "http",
                    connectMetricTagProtocol = "http",
                    connectMetricTagIsMultiplex = false,
                    otherMetricsTagProtocol = "h2c",
                    otherMetricsTagIsMultiplex = true
                ),
                CaseArguments(
                    clientProtocol = "h1c",
                    connectMetricTagProtocol = "h1c",
                    connectMetricTagIsMultiplex = false,
                    otherMetricsTagProtocol = "h1c",
                    otherMetricsTagIsMultiplex = false
                ),
                CaseArguments(
                    clientProtocol = "h2c",
                    connectMetricTagProtocol = "h2c",
                    connectMetricTagIsMultiplex = true,
                    otherMetricsTagProtocol = "h2c",
                    otherMetricsTagIsMultiplex = true
                )
            )

        }
    }

    object ErrorProfilingTest {
        const val TEST_PATH = "/test-error-profiling"

        object Utils {

            fun delayedResponsePartsMock(
                targetPath: String,
                serverRequestTimeout: Duration,
                fullResponseDelay: Duration,
                responseStatus: HttpStatus,
                numberOfResponseParts: Int
            ): ArmeriaMockServer {
                return ArmeriaMockServer {
                    this.requestTimeout(serverRequestTimeout)
                        .service(targetPath) { ctx, _ ->
                            HttpResponse.streaming().also { response ->
                                val responseStatusHeader = ResponseHeaders.of(responseStatus)
                                response.write(responseStatusHeader)

                                val lastResponsePartIndex = numberOfResponseParts - 1
                                for (responsePartIndex in 0..numberOfResponseParts) {
                                    ctx.eventLoop().schedule(
                                        {
                                            response.write(
                                                HttpData.ofAscii(responsePartIndex.toString())
                                            )
                                            if (responsePartIndex == lastResponsePartIndex) {
                                                response.close()
                                            }
                                        },
                                        fullResponseDelay.toMillis() * responsePartIndex / numberOfResponseParts,
                                        TimeUnit.MILLISECONDS
                                    )
                                }
                            }
                        }
                }
            }

        }

        data class ProtocolSpecificCase(
            val protocol: SessionProtocol,
            val caseName: String,
            val profiledErrorType: String
        )

        data class Case(
            val testCaseName: String,
            val mockServerGenerator: suspend () -> MockServer,
            val expectedErrorMetricTagsByMockUriGenerator: (URI) -> List<MetricTag>,
            val latencyMetricRequired: Boolean = false,
            val clientBuilderCustomizer: WebClientBuilder.() -> WebClientBuilder = { this }
        ) {

            class MockServer(
                val mockUri: URI,
                val stop: suspend () -> Unit
            )

        }

    }

}