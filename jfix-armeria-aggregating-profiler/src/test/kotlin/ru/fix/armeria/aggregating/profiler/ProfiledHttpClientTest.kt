package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ClientOption
import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.WebClientBuilder
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.client.retry.RetryingClient
import com.linecorp.armeria.common.*
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.testing.junit.server.ServerExtension
import com.linecorp.armeria.testing.junit.server.mock.MockWebServerExtension
import io.kotest.assertions.assertSoftly
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import ru.fix.aggregating.profiler.Identity
import ru.fix.aggregating.profiler.NoopProfiler
import ru.fix.aggregating.profiler.ProfiledCall
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.ProfilerVerifyContext.Companion.withVerifyScope
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.mockHttpConnectCall
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.mockHttpConnectedCall
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.mockHttpErrorCall
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.mockHttpSuccessCall
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.verifyConnect
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.verifyConnected
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.verifyErrorWithProfiledLatency
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.verifyErrorWithoutLatency
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClientTest.Companion.MockVerifyUtils.verifySuccess
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.EPOLL_SOCKET_CHANNEL
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.LOCAL_ADDRESS
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.LOCAL_HOST
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.localhostHttpUri
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.localhostUri
import ru.fix.armeria.commons.unwrapUnprocessedExceptionIfNecessary
import ru.fix.stdlib.socket.SocketChecker
import java.net.ConnectException
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.CONCURRENT)
internal class ProfiledHttpClientTest {

    @ParameterizedTest
    @ArgumentsSource(ProtocolSpecificTest.CaseArgumentsProvider::class)
    fun `success request profiled with connect, connected and whole request flow metrics`(
        testCaseArguments: ProtocolSpecificTest.CaseArguments
    ) {
        val pathDelayedOk = "/ok-delayed/{delay}"
        val mockServer = object : MockWebServerExtension() {
            override fun configureServer(sb: ServerBuilder) {
                sb.service(pathDelayedOk) { ctx, _ ->
                    val delayMs = ctx.pathParam("delay")!!.toLong()
                    HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(delayMs))
                }
            }
        }
        mockServer.start()
        mockServer.server().use {

            val mockedProfiler = spyk(NoopProfiler())
            val (mockedConnectCall, mockedConnectedCall, mockedSuccessCall) = Triple(
                mockedProfiler.mockHttpConnectCall(),
                mockedProfiler.mockHttpConnectedCall(),
                mockedProfiler.mockHttpSuccessCall()
            )
            val localhostUri = mockServer.localhostUri(SessionProtocol.find(testCaseArguments.clientProtocol)!!)
            val client = WebClient
                .builder(localhostUri)
                .decorator(ProfiledHttpClient.newDecorator(mockedProfiler))
                .build()
            val path = pathDelayedOk.replace("{delay}", 1_000.toString())
            val expectedConnectMetricTags = mapOf(
                MetricTags.METHOD to "GET",
                MetricTags.PATH to path,
                MetricTags.PROTOCOL to testCaseArguments.connectMetricTagProtocol,
                MetricTags.IS_MULTIPLEX_PROTOCOL to testCaseArguments.connectMetricTagIsMultiplex.toString()
            )
            val expectedConnectedMetricTags = expectedConnectMetricTags + mapOf(
                MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                MetricTags.REMOTE_HOST to LOCAL_HOST,
                MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                //protocol and channel information are determined on this phase
                MetricTags.PROTOCOL to testCaseArguments.otherMetricsTagProtocol,
                MetricTags.IS_MULTIPLEX_PROTOCOL to testCaseArguments.otherMetricsTagIsMultiplex.toString(),
                MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL
            )
            val expectedSuccessMetricTags = expectedConnectedMetricTags + (MetricTags.RESPONSE_STATUS to "200")

            client.get(path).aggregate().join()

            val profilerCapturer = MockVerifyUtils.ProfilerInvader()
            verifySequence {
                //profile connect without chosen endpoint
                withVerifyScope(mockedProfiler to mockedConnectCall) verifyConnect {
                    capture(profilerCapturer.connectSlot)
                }

                //profile when connection established
                withVerifyScope(mockedProfiler to mockedConnectedCall) verifyConnected {
                    capture(profilerCapturer.connectedSlot)
                }

                //profile whole request execution
                withVerifyScope(mockedProfiler to mockedSuccessCall) verifySuccess {
                    capture(profilerCapturer.successSlot)
                }
            }
            assertSoftly(profilerCapturer) {
                assertSoftly(connectSlot.captured) {
                    name shouldBe Metrics.HTTP_CONNECT
                    tags shouldContainExactly expectedConnectMetricTags
                }
                assertSoftly(connectedSlot.captured) {
                    name shouldBe Metrics.HTTP_CONNECTED
                    tags shouldContainExactly expectedConnectedMetricTags
                }
                assertSoftly(successSlot.captured) {
                    name shouldBe Metrics.HTTP_SUCCESS
                    tags shouldContainExactly expectedSuccessMetricTags
                }
            }
        }
    }


    @Nested
    inner class `Http (and not only) errors` {

        @TestFactory
        fun `WHEN error occured THEN it is profiled with corresponding status`() = DynamicTest.stream(

            listOf(

                ErrorProfilingTest.Case(
                    testCaseName = "Invalid (non 2xx) status 404",
                    mockServerGenerator = {
                        val mockServer = object : ServerExtension() {
                            override fun configure(sb: ServerBuilder) {
                                sb.service("/some-other-path") { _, _ -> HttpResponse.of(HttpStatus.OK) }
                            }

                        }
                        mockServer.start()
                        ErrorProfilingTest.Case.MockServer(mockServer.localhostHttpUri()) {
                            mockServer.stop().join()
                        }
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.ERROR_TYPE to "invalid_status",
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
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
                        val mockServer = object : ServerExtension() {
                            override fun configure(sb: ServerBuilder) {
                                sb.service(ErrorProfilingTest.TEST_PATH) { _, _ ->
                                    HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
                                }
                            }

                        }
                        mockServer.start()
                        ErrorProfilingTest.Case.MockServer(mockServer.localhostHttpUri()) {
                            mockServer.stop().join()
                        }
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.ERROR_TYPE to "invalid_status",
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
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
                        ErrorProfilingTest.Case.MockServer(URI.create("http://$LOCAL_HOST:${getAvailableRandomPort()}")) {}
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.ERROR_TYPE to "connect_refused",
                            MetricTags.PATH to ErrorProfilingTest.TEST_PATH,
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "http",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString()
                        )
                    },
                    latencyMetricRequired = true
                ),

                ErrorProfilingTest.Case(
                    testCaseName = "Response timeout",
                    mockServerGenerator = {
                        val server = MockWebServerExtension().apply {
                            start()
                            enqueue(HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofSeconds(2)))
                        }

                        ErrorProfilingTest.Case.MockServer(server.localhostHttpUri()) {
                            server.stop()
                        }
                    },
                    expectedErrorMetricTagsByMockUriGenerator = {
                        listOf(
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
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
                        option(ClientOption.RESPONSE_TIMEOUT_MILLIS, 500)
                    }
                ),

                *listOf(
                    ErrorProfilingTest.ProtocolSpecificCase(
                        protocol = SessionProtocol.H1C,
                        caseName = """Session closed of "http/1 - cleartext" request 
                                |due to server side response timeout""".trimMargin(),
                        profiledErrorType = "response_closed_session"
                    ),
                    ErrorProfilingTest.ProtocolSpecificCase(
                        protocol = SessionProtocol.H2C,
                        caseName = """Stream closed of "http/2 - cleartext" request 
                                |due to server side response timeout""".trimMargin(),
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
                            ErrorProfilingTest.Case.MockServer(server.localhostUri(protocolSpecificCase.protocol)) {
                                server.stop()
                            }
                        },
                        expectedErrorMetricTagsByMockUriGenerator = {
                            listOf(
                                MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                                MetricTags.REMOTE_HOST to LOCAL_HOST,
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
        ) { (_, setupMockForError: () -> ErrorProfilingTest.Case.MockServer,
                expectedErrorMetricTags: (URI) -> List<MetricTag>,
                latencyMetricRequired: Boolean,
                webClientBuilderCustomizer: WebClientBuilder.() -> WebClientBuilder) ->

            setupMockForError().use { mockServer ->
                val mockedProfiler = spyk(NoopProfiler()) {
                    excludeRecords { //connect and connected metrics are checked by other tests
                        profiledCall(match<Identity> { it.name in setOf(Metrics.HTTP_CONNECT, Metrics.HTTP_CONNECTED) })
                    }
                }
                val mockedErrorCall = mockedProfiler.mockHttpErrorCall()
                val client = WebClient.builder(mockServer.mockUri)
                    .decorator(ProfiledHttpClient.newDecorator(mockedProfiler))
                    .webClientBuilderCustomizer()
                    .build()

                try {
                    client.get(ErrorProfilingTest.TEST_PATH).aggregate().join()
                } catch (e: Exception) {
                    //ignored
                }

                val identitySlot = slot<Identity>()
                verifySequence {
                    withVerifyScope(mockedProfiler to mockedErrorCall) {
                        if (latencyMetricRequired) {
                            verifyErrorWithProfiledLatency {
                                capture(identitySlot)
                            }
                        } else {
                            verifyErrorWithoutLatency {
                                capture(identitySlot)
                            }
                        }
                    }
                }
                assertSoftly(identitySlot.captured) {
                    name shouldBe Metrics.HTTP_ERROR
                    tags shouldContainExactly expectedErrorMetricTags(mockServer.mockUri).toMap()
                }
            }
        }

        @Test
        fun `WHEN connection unsuccessful THEN connected metric is not occured`() {
            val mockedProfiler = spyk(NoopProfiler()) {
                excludeRecords {
                    profiledCall(match<Identity> { it.name in setOf(Metrics.HTTP_CONNECT, Metrics.HTTP_ERROR) })
                }
            }
            val client = WebClient
                .builder(SessionProtocol.HTTP, Endpoint.of(LOCAL_HOST, getAvailableRandomPort()))
                .decorator(ProfiledHttpClient.newDecorator(mockedProfiler))
                .build()

            try {
                client.get("/").aggregate().join()
            } catch (e: Exception) {
            }

            verify { mockedProfiler wasNot Called }
            confirmVerified(mockedProfiler)
        }

        @Test
        fun `WHEN endpoint group is empty THEN error profiled`() {
            val testPath = "/test-no-endpoint-group-error"
            val profiler: Profiler = spyk(NoopProfiler()) {
                excludeRecords {
                    profiledCall(match<Identity> { it.name == Metrics.HTTP_CONNECT })
                    profiledCall(match<Identity> { it.name == Metrics.HTTP_CONNECTED })
                }
            }
            val mockedErrorCall = profiler.mockHttpErrorCall()
            val client = WebClient.builder(SessionProtocol.HTTP, EndpointGroup.empty())
                .decorator(ProfiledHttpClient.newDecorator(profiler))
                .build()

            try {
                client.get(testPath).aggregate().join()
            } catch (e: Exception) {
                //ignored
            }

            val identitySlot = slot<Identity>()
            verifySequence {
                withVerifyScope(profiler to mockedErrorCall) verifyErrorWithoutLatency {
                    capture(identitySlot)
                }
            }
            assertSoftly(identitySlot.captured) {
                name shouldBe Metrics.HTTP_ERROR
                tags shouldContainExactly mapOf(
                    MetricTags.ERROR_TYPE to "no_available_endpoint",
                    MetricTags.PATH to testPath,
                    MetricTags.METHOD to "GET",
                    MetricTags.PROTOCOL to "http",
                    MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString()
                )
            }
        }

    }

    @Nested
    inner class `WHEN retry decorator placed` {

        @Test
        fun `before profiled one THEN each retry attempt profiled`() {
            val mockServer = MockWebServerExtension().apply {
                start()
            }
            mockServer.server().use {
                val mockedProfiler = spyk(NoopProfiler())
                val expectedMetricsCount = 8
                mockServer.enqueue(
                    HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                        Duration.ofMillis(500)
                    )
                )
                mockServer.enqueue(
                    HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(200))
                )
                val retryRule = RetryRule.onStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .orElse(RetryRule.onException { it.unwrapUnprocessedExceptionIfNecessary() is ConnectException })
                val client = WebClient
                    .builder(
                        SessionProtocol.HTTP,
                        EndpointGroup.of(
                            EndpointSelectionStrategy.roundRobin(),
                            Endpoint.of(LOCAL_HOST, mockServer.httpPort()),
                            Endpoint.of(LOCAL_HOST, getAvailableRandomPort())
                        )
                    )
                    .decorator(ProfiledHttpClient.newDecorator(mockedProfiler))
                    .decorator(RetryingClient.newDecorator(retryRule, 3))
                    .build()

                client.get("/").aggregate().join()

                val capturedProfilerIdentities = mutableListOf<Identity>()
                verify(exactly = expectedMetricsCount) {
                    mockedProfiler.profiledCall(
                        capture(capturedProfilerIdentities)
                    )
                }
                confirmVerified(mockedProfiler)
                capturedProfilerIdentities shouldHaveSize expectedMetricsCount
                assertSoftly {
                    val secondRequestIndex = 3
                    val thirdRequestIndex = 5
                    listOf(
                        capturedProfilerIdentities.first(), //1st metric of 1st request
                        capturedProfilerIdentities[secondRequestIndex], //1st metric of 2nd request
                        capturedProfilerIdentities[thirdRequestIndex] //1st metric of 3rd request
                    ).forAll {
                        it.name shouldBe Metrics.HTTP_CONNECT
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "http",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString()
                        )
                    }
                    // Requests to valid mock server
                    listOf(
                        capturedProfilerIdentities[1], //2nd metric of 1st request
                        capturedProfilerIdentities[thirdRequestIndex + 1] //2nd metric of 3rd request
                    ).forAll {
                        it.name shouldBe Metrics.HTTP_CONNECTED
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
                            MetricTags.REMOTE_PORT to mockServer.httpPort().toString()
                        )
                    }
                    //error metric of 1st requests
                    capturedProfilerIdentities[2] should {
                        it.name shouldBe Metrics.HTTP_ERROR
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
                            MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                            MetricTags.ERROR_TYPE to "invalid_status",
                            MetricTags.RESPONSE_STATUS to "503"
                        )
                    }
                    //error metric of 2nd requests
                    capturedProfilerIdentities[secondRequestIndex + 1] should {
                        it.name shouldBe Metrics.HTTP_ERROR
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "http",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString(),
                            MetricTags.ERROR_TYPE to "connect_refused"
                        )
                    }
                    capturedProfilerIdentities.last() should {
                        it.name shouldBe Metrics.HTTP_SUCCESS
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
                            MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                            MetricTags.RESPONSE_STATUS to "200"
                        )
                    }
                }
            }
        }

        @Test
        fun `after profiled one AND attempts are not exceeded THEN all retries profiled as one success request`() {
            val mockedSuccessCall = spyk(NoopProfiler.NoopProfiledCall())
            val mockedProfiler = spyk(NoopProfiler()) {
                every {
                    profiledCall(match<Identity> { it.name == Metrics.HTTP_SUCCESS })
                } returns mockedSuccessCall
            }
            val expectedMetricsCount = 3
            val mockServer = MockWebServerExtension()
            val mockServer2 = MockWebServerExtension()
            val firstRequestDelayMillis: Long = 500
            val lastRequestDelayMillis: Long = 200
            try {
                mockServer.start()
                mockServer2.start()

                mockServer.enqueue(
                    HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                        Duration.ofMillis(firstRequestDelayMillis)
                    )
                )
                mockServer2.enqueue(
                    HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(lastRequestDelayMillis))
                )
                val retryRule = RetryRule.onStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .orElse(RetryRule.onException { it.unwrapUnprocessedExceptionIfNecessary() is ConnectException })
                val client = WebClient
                    .builder(
                        SessionProtocol.HTTP,
                        EndpointGroup.of(
                            EndpointSelectionStrategy.roundRobin(),
                            Endpoint.of(LOCAL_HOST, mockServer.httpPort()),
                            Endpoint.of(LOCAL_HOST, getAvailableRandomPort()),
                            Endpoint.of(LOCAL_HOST, mockServer2.httpPort())
                        )
                    )
                    .decorator(RetryingClient.newDecorator(retryRule, 3))
                    .decorator(ProfiledHttpClient.newDecorator(mockedProfiler))
                    .build()
                /*
                TODO
                 due to details of context logs when retries are present (see ProfiledHttpClient implementation),
                 first endpoint's data is written to metrics. It would be better to fix it or reorganize structure of
                 metrics for whole request flow processing.
                 */
                val expectedProfiledMockServer = mockServer/*2*/

                client.get("/").aggregate().join()
                val requestEndTimestamp = System.currentTimeMillis()

                val capturedProfilerIdentities = mutableListOf<Identity>()
                val successCallSlot = slot<Long>()
                verify(exactly = expectedMetricsCount) {
                    mockedProfiler.profiledCall(
                        capture(capturedProfilerIdentities)
                    )
                }
                verify {
                    mockedSuccessCall.call(
                        capture(successCallSlot)
                    )
                }
                confirmVerified(mockedProfiler, mockedSuccessCall)
                capturedProfilerIdentities shouldHaveSize expectedMetricsCount
                assertSoftly {
                    capturedProfilerIdentities.first() should {
                        it.name shouldBe Metrics.HTTP_CONNECT
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "http",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString()
                        )
                    }
                    capturedProfilerIdentities[1] should {
                        it.name shouldBe Metrics.HTTP_CONNECTED
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
                            MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString()
                        )
                    }
                    capturedProfilerIdentities.last() should {
                        it.name shouldBe Metrics.HTTP_SUCCESS
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
                            MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString(),
                            MetricTags.RESPONSE_STATUS to "200"
                        )
                    }
                    (requestEndTimestamp - successCallSlot.captured) shouldBeGreaterThan
                            firstRequestDelayMillis + lastRequestDelayMillis
                }
            } finally {
                mockServer.stop()
                mockServer2.stop()
            }
        }

        @Test
        fun `after profiled one AND attempts are exceeded THEN all retries profiled as one error request`() {
            val mockedErrorCall = spyk(NoopProfiler.NoopProfiledCall())
            val mockedProfiler = spyk(NoopProfiler()) {
                every {
                    profiledCall(match<Identity> { it.name == Metrics.HTTP_ERROR })
                } returns mockedErrorCall
            }
            val expectedMetricsCount = 3
            val mockServer = MockWebServerExtension()
            val mockServer2 = MockWebServerExtension()
            val firstRequestDelayMillis: Long = 400
            val lastRequestDelayMillis: Long = 300
            try {
                mockServer.start()
                mockServer2.start()

                mockServer.enqueue(
                    HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                        Duration.ofMillis(firstRequestDelayMillis)
                    )
                )
                mockServer2.enqueue(
                    HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE),
                        Duration.ofMillis(lastRequestDelayMillis)
                    )
                )
                val retryRule = RetryRule.onStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .orElse(RetryRule.onException { it.unwrapUnprocessedExceptionIfNecessary() is ConnectException })
                val client = WebClient
                    .builder(
                        SessionProtocol.HTTP,
                        EndpointGroup.of(
                            EndpointSelectionStrategy.roundRobin(),
                            Endpoint.of(LOCAL_HOST, mockServer.httpPort()),
                            Endpoint.of(LOCAL_HOST, getAvailableRandomPort()),
                            Endpoint.of(LOCAL_HOST, mockServer2.httpPort())
                        )
                    )
                    .decorator(RetryingClient.newDecorator(retryRule, 3))
                    .decorator(ProfiledHttpClient.newDecorator(mockedProfiler))
                    .build()
                /*
                TODO
                 due to details of context logs when retries are present (see ProfiledHttpClient implementation),
                 first endpoint's data is written to metrics. It would be better to fix it or reorganize structure of
                 metrics for whole request flow processing.
                 */
                val expectedProfiledMockServer = mockServer/*2*/

                client.get("/").aggregate().join()
                val requestEndTimestamp = System.currentTimeMillis()

                val capturedProfilerIdentities = mutableListOf<Identity>()
                val successCallSlot = slot<Long>()
                verify(exactly = expectedMetricsCount) {
                    mockedProfiler.profiledCall(
                        capture(capturedProfilerIdentities)
                    )
                }
                verify {
                    mockedErrorCall.call(
                        capture(successCallSlot)
                    )
                }
                confirmVerified(mockedProfiler, mockedErrorCall)
                capturedProfilerIdentities shouldHaveSize expectedMetricsCount
                assertSoftly {
                    capturedProfilerIdentities.first() should {
                        it.name shouldBe Metrics.HTTP_CONNECT
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "http",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to false.toString()
                        )
                    }
                    capturedProfilerIdentities[1] should {
                        it.name shouldBe Metrics.HTTP_CONNECTED
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
                            MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString()
                        )
                    }
                    capturedProfilerIdentities.last() should {
                        it.name shouldBe Metrics.HTTP_ERROR
                        it.tags shouldContainExactly mapOf(
                            MetricTags.PATH to "/",
                            MetricTags.METHOD to "GET",
                            MetricTags.PROTOCOL to "h2c",
                            MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL,
                            MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                            MetricTags.REMOTE_HOST to LOCAL_HOST,
                            MetricTags.REMOTE_PORT to expectedProfiledMockServer.httpPort().toString(),
                            MetricTags.ERROR_TYPE to "invalid_status",
                            MetricTags.RESPONSE_STATUS to "503"
                        )
                    }
                    (requestEndTimestamp - successCallSlot.captured) shouldBeGreaterThan
                            firstRequestDelayMillis + lastRequestDelayMillis
                }
            } finally {
                mockServer.stop()
                mockServer2.stop()
            }
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

    companion object {

        fun getAvailableRandomPort() = SocketChecker.getAvailableRandomPort()

        object MockVerifyUtils {
            class ProfilerInvader {
                val connectSlot = slot<Identity>()
                val connectedSlot = slot<Identity>()
                val successSlot = slot<Identity>()
            }

            private fun Profiler.mockCallMatchingName(name: String) =
                spyk(NoopProfiler.NoopProfiledCall()).also { call ->
                    every {
                        profiledCall(match<Identity> {
                            it.name == name
                        })
                    } returns call
                }

            fun Profiler.mockHttpConnectCall(): ProfiledCall = mockCallMatchingName(Metrics.HTTP_CONNECT)
            fun Profiler.mockHttpConnectedCall(): ProfiledCall = mockCallMatchingName(Metrics.HTTP_CONNECTED)
            fun Profiler.mockHttpSuccessCall(): ProfiledCall = mockCallMatchingName(Metrics.HTTP_SUCCESS)
            fun Profiler.mockHttpErrorCall(): ProfiledCall = mockCallMatchingName(Metrics.HTTP_ERROR)


            class ProfilerVerifyContext private constructor(
                val verificationScope: MockKVerificationScope,
                val mockedProfiler: Profiler,
                val mockedCall: ProfiledCall
            ) {
                companion object {
                    fun MockKVerificationScope.withVerifyScope(profilerPair: Pair<Profiler, ProfiledCall>): ProfilerVerifyContext =
                        ProfilerVerifyContext(this, profilerPair.first, profilerPair.second)

                    fun MockKVerificationScope.withVerifyScope(
                        profilerPair: Pair<Profiler, ProfiledCall>,
                        block: ProfilerVerifyContext.() -> Unit
                    ) {
                        withVerifyScope(profilerPair).block()
                    }
                }

            }

            infix fun ProfilerVerifyContext.verifyConnect(capture: () -> Identity) {
                mockedProfiler.profiledCall(capture())
                mockedCall.start()
                mockedCall.stop()
            }

            infix fun ProfilerVerifyContext.verifyConnected(capture: () -> Identity) {
                mockedProfiler.profiledCall(capture())
                mockedCall.call(verificationScope.any<Long>())
            }

            infix fun ProfilerVerifyContext.verifySuccess(capture: () -> Identity) {
                mockedProfiler.profiledCall(capture())
                mockedCall.call(verificationScope.any<Long>())
            }

            private fun ProfilerVerifyContext.verifyError(latencyProfiled: Boolean, capture: () -> Identity) {
                mockedProfiler.profiledCall(capture())
                mockedCall.run {
                    if (latencyProfiled) {
                        call(verificationScope.any<Long>())
                    } else {
                        call()
                    }
                }
            }

            infix fun ProfilerVerifyContext.verifyErrorWithProfiledLatency(capture: () -> Identity) {
                verifyError(true, capture)
            }

            infix fun ProfilerVerifyContext.verifyErrorWithoutLatency(capture: () -> Identity) {
                verifyError(false, capture)
            }
        }

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
            ): ServerExtension {
                return object : ServerExtension() {
                    override fun configure(sb: ServerBuilder) {
                        sb.requestTimeout(serverRequestTimeout)

                        sb.service(targetPath) { ctx, _ ->
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

        }

        data class ProtocolSpecificCase(
            val protocol: SessionProtocol,
            val caseName: String,
            val profiledErrorType: String
        )

        data class Case(
            val testCaseName: String,
            val mockServerGenerator: () -> MockServer,
            val expectedErrorMetricTagsByMockUriGenerator: (URI) -> List<MetricTag>,
            val latencyMetricRequired: Boolean = false,
            val clientBuilderCustomizer: WebClientBuilder.() -> WebClientBuilder = { this }
        ) {

            class MockServer(
                val mockUri: URI,
                private val stop: () -> Unit
            ) : AutoCloseable {
                override fun close() {
                    stop()
                }
            }

        }

    }

}