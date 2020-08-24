package ru.fix.armeria.limiter

import com.linecorp.armeria.client.DecoratingHttpClientFunction
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.future.asDeferred
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.Test
import retrofit2.create
import retrofit2.http.GET
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.aggregating.profiler.Identity
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.limiter.ProfilerTestUtils.indicatorWithNameEnding
import ru.fix.armeria.limiter.ProfilerTestUtils.profiledCallReportWithNameEnding
import ru.fix.dynamic.property.api.DynamicProperty
import ru.fix.stdlib.concurrency.threads.NamedExecutors
import ru.fix.stdlib.ratelimiter.ConfigurableRateLimiter
import ru.fix.stdlib.ratelimiter.RateLimitedDispatcher
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

private typealias MockServerStopper = suspend () -> Unit
private typealias MockServerCreatorResult = Pair<URI, MockServerStopper>
private typealias MockServerCreator = suspend (Int) -> MockServerCreatorResult
private typealias ClientCreator<ClientT> = (URI, RateLimitedDispatcher, Profiler) -> ClientT
private typealias ClientWithProfiledCallCreator<ClientT>
        = (DecoratingHttpClientFunction, URI, RateLimitedDispatcher, Profiler) -> ClientT

private typealias ClientToServerLoadEmulator<ClientT> = suspend (Int, ClientT) -> Unit

@ExperimentalTime
internal class RateLimitingClientTest {

    @Test
    suspend fun `request completion releases resources for 'WebClient'`() {
        `request completion releases resources`(
            rateLimiterName = "WebClient-test-release-limiter",
            clientCreator = { uri, rateLimitedDispatcher, _ ->
                WebClient.builder(uri)
                    .decorator(
                        RateLimitingHttpClient.newDecorator(rateLimitedDispatcher)
                    )
                    .build()
            },
            execute = {
                get("/").aggregate().asDeferred()
            }
        )
    }

    @Test
    suspend fun `request completion releases resources for 'retrofit streaming client'`() {
        `request completion releases resources`(
            rateLimiterName = "retrofit-streaming-client-test-release-limiter",
            clientCreator = { uri, rateLimitedDispatcher, profiler ->
                val callbackExecutor =
                    NamedExecutors.newDynamicPool(
                        "retrofit-streaming-client-test-response-reading-pool",
                        DynamicProperty.of(3),
                        profiler
                    )
                val baseWebClient = WebClient.builder(uri)
                    .decorator(
                        RateLimitingHttpClient.newDecorator(rateLimitedDispatcher)
                    )
                    .build()
                val retrofit = ArmeriaRetrofit
                    .builder(baseWebClient)
                    .streaming(true)
                    .callbackExecutor(callbackExecutor)
                    .build()
                retrofit.create<TestClient>()
            },
            execute = {
                GlobalScope.async {
                    testGet()
                }
            }
        )
    }

    @Test
    suspend fun `request completion releases resources for 'retrofit blocking client'`() {
        `request completion releases resources`(
            rateLimiterName = "retrofit-blocking-client-test-release-limiter",
            clientCreator = { uri, rateLimitedDispatcher, _ ->
                val baseWebClient = WebClient.builder(uri)
                    .decorator(
                        RateLimitingHttpClient.newDecorator(rateLimitedDispatcher)
                    )
                    .build()
                val retrofit = ArmeriaRetrofit
                    .builder(baseWebClient)
                    .build()
                retrofit.create<TestClient>()
            },
            execute = {
                GlobalScope.async {
                    testGet()
                }
            }
        )
    }

    @Test
    suspend fun `RPS restricted by rate limiter dispatcher for 'WebClient'`() {
        `emulate load to server with specified client and check throughput`<WebClient>(
            rateLimiterName = "WebClient-limiter",
            targetPermitsPerSec = 100,
            windowProperty = DynamicProperty.of(200),
            requestsCount = 400,
            mockServerCreator = { requestsCount ->
                mockServerWithResponsesDelayedByIndexMod(requestsCount).let {
                    it.start()
                    MockServerCreatorResult(it.httpUri()) {
                        it.stop()
                    }
                }
            },
            createHttpClientWithProfiledCall = { testProfiledCallDecorator, uri, rateLimitedDispatcher, _ ->
                WebClient.builder(uri)
                    .decorator(testProfiledCallDecorator)
                    .decorator(
                        RateLimitingHttpClient.newDecorator(rateLimitedDispatcher)
                    ).build()
            },
            emulateLoadFromClientToServer = { requestsCount, webClient ->
                List(requestsCount) {
                    webClient.get("/").aggregate().asDeferred()
                }.awaitAll()
            }
        )
    }

    @Test
    suspend fun `RPS restricted by rate limiter dispatcher for 'streaming retrofit'`() {
        `emulate load to server with specified client and check throughput`<TestClient>(
            rateLimiterName = "retrofit-streaming-client-limiter",
            targetPermitsPerSec = 100,
            windowProperty = DynamicProperty.of(200),
            requestsCount = 400,
            mockServerCreator = { requestsCount ->
                mockServerWithResponsesDelayedByIndexMod(requestsCount).let {
                    it.start()
                    MockServerCreatorResult(it.httpUri()) {
                        it.stop()
                    }
                }
            },
            createHttpClientWithProfiledCall = { testProfiledCallDecorator, uri, rateLimitedDispatcher, profiler ->
                val callbackExecutor =
                    NamedExecutors.newDynamicPool(
                        "retrofit-streaming-client-test-response-reading-pool",
                        DynamicProperty.of(5),
                        profiler
                    )
                val baseWebClient = WebClient.builder(uri)
                    .decorator(testProfiledCallDecorator)
                    .decorator(
                        RateLimitingHttpClient.newDecorator(rateLimitedDispatcher)
                    )
                    .build()
                val retrofit = ArmeriaRetrofit
                    .builder(baseWebClient)
                    .streaming(true)
                    .callbackExecutor(callbackExecutor)
                    .build()
                retrofit.create()
            },
            emulateLoadFromClientToServer = { requestsCount, testClient ->
                List(requestsCount) {
                    GlobalScope.async {
                        testClient.testGet()
                    }
                }.awaitAll()
            }
        )
    }

    @Test
    suspend fun `RPS restricted by rate limiter dispatcher for 'blocking retrofit'`() {
        `emulate load to server with specified client and check throughput`<TestClient>(
            rateLimiterName = "retrofit-blocking-client-limiter",
            targetPermitsPerSec = 100,
            windowProperty = DynamicProperty.of(200),
            requestsCount = 400,
            mockServerCreator = { requestsCount ->
                mockServerWithResponsesDelayedByIndexMod(requestsCount).let {
                    it.start()
                    MockServerCreatorResult(it.httpUri()) {
                        it.stop()
                    }
                }
            },
            createHttpClientWithProfiledCall = { testProfiledCallDecorator, uri, rateLimitedDispatcher, _ ->
                val baseWebClient = WebClient.builder(uri)
                    .decorator(testProfiledCallDecorator)
                    .decorator(
                        RateLimitingHttpClient.newDecorator(rateLimitedDispatcher)
                    )
                    .build()
                val retrofit = ArmeriaRetrofit
                    .builder(baseWebClient)
                    .build()
                retrofit.create()
            },
            emulateLoadFromClientToServer = { requestsCount, testClient ->
                List(requestsCount) {
                    GlobalScope.async {
                        testClient.testGet()
                    }
                }.awaitAll()
            }
        )
    }

    companion object : Logging {

        const val TEST_METRIC_NAME = "test_metric"

        const val ACTIVE_ASYNC_OPERATIONS_METRIC = "active_async_operations"

        interface TestClient {
            @GET("/")
            suspend fun testGet()
        }

        fun Map<Identity, Long>.indicatorWithNameEnding(nameEnding: String): Long =
            this.mapKeys { it.key.name }.toList().single { (name, _) ->
                name.endsWith(nameEnding)
            }.second

        private fun mockServerWithResponsesDelayedByIndexMod(
            numberOfResponses: Int,
            requestIndexMod: Int = 10,
            smalledRequestDelayMs: Long = 100
        ): ArmeriaMockServer = ArmeriaMockServer().apply {
            for (requestIndex in 1..numberOfResponses) {
                val requestDelayMs = (requestIndex % requestIndexMod) * smalledRequestDelayMs
                enqueue(
                    HttpResponse.delayed(
                        HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK), HttpData.ofUtf8("$requestIndex")
                        ),
                        Duration.ofMillis(requestDelayMs)
                    )
                )
            }
        }

        private suspend fun <ClientT> `emulate load to server with specified client and check throughput`(
            rateLimiterName: String,
            targetPermitsPerSec: Int,
            windowProperty: DynamicProperty<Long>,
            requestsCount: Int,
            mockServerCreator: MockServerCreator,
            createHttpClientWithProfiledCall: ClientWithProfiledCallCreator<ClientT>,
            emulateLoadFromClientToServer: ClientToServerLoadEmulator<ClientT>
        ) {
            val targetPermitsPerSecDouble = targetPermitsPerSec.toDouble()
            val (mockServerUri, mockServerStopper) = mockServerCreator(requestsCount)
            try {
                val profiler = AggregatingProfiler()
                val rateLimiter = ConfigurableRateLimiter(
                    rateLimiterName,
                    targetPermitsPerSec
                )
                RateLimitedDispatcher("$rateLimiter-dispatcher", rateLimiter, profiler, windowProperty)
                    .use { rateLimitedDispatcher ->
                        val testProfiledCallDecorator = DecoratingHttpClientFunction { delegate, ctx, req ->
                            profiler.profile(TEST_METRIC_NAME, Supplier {
                                delegate.execute(ctx, req)
                            })
                        }
                        val client =
                            createHttpClientWithProfiledCall(
                                testProfiledCallDecorator,
                                mockServerUri,
                                rateLimitedDispatcher,
                                profiler
                            )
                        val reporter = profiler.createReporter()

                        logger.info { "Submitting $requestsCount requests..." }
                        emulateLoadFromClientToServer(requestsCount, client)
                        logger.info { "Requests completed. Building profiler report..." }

                        eventually(1.seconds) {
                            val report = reporter.buildReportAndReset()
                            logger.info { "Report: $report" }
                            assertSoftly(report) {
                                profiledCallReportWithNameEnding(TEST_METRIC_NAME) should {
                                    it.shouldNotBeNull()
                                    it.startThroughputAvg.shouldBeBetween(
                                        targetPermitsPerSecDouble,
                                        targetPermitsPerSecDouble,
                                        targetPermitsPerSecDouble * 0.25
                                    )
                                }
                                indicators.indicatorWithNameEnding(ACTIVE_ASYNC_OPERATIONS_METRIC) shouldBe 0
                            }
                        }
                    }
            } finally {
                mockServerStopper()
            }
        }

        private suspend fun <ClientT> `request completion releases resources`(
            rateLimiterName: String,
            clientCreator: ClientCreator<ClientT>,
            execute: ClientT.() -> Deferred<*>
        ) {
            val responseFuture = CompletableFuture<HttpResponse>()
            val mockServer = ArmeriaMockServer().apply {
                enqueue(HttpResponse.from(responseFuture))
            }
            mockServer.start()
            try {
                val profiler = AggregatingProfiler()
                val rateLimiter = ConfigurableRateLimiter(
                    rateLimiterName,
                    1000
                )
                val windowProperty = DynamicProperty.of(1L)
                RateLimitedDispatcher("$rateLimiterName-dispatcher", rateLimiter, profiler, windowProperty)
                    .use { rateLimitedDispatcher ->
                        val client = clientCreator(mockServer.httpUri(), rateLimitedDispatcher, profiler)
                        val profilerReporter = profiler.createReporter()

                        val requestFuture = client.execute()
                        eventually(1.seconds) {
                            profilerReporter.buildReportAndReset() should { report ->
                                report.indicatorWithNameEnding(ACTIVE_ASYNC_OPERATIONS_METRIC) should {
                                    it shouldBe 1
                                }
                            }
                        }
                        responseFuture.complete(HttpResponse.of(HttpStatus.OK))
                        requestFuture.await()
                        eventually(1.seconds) {
                            profilerReporter.buildReportAndReset() should { report ->
                                report.indicatorWithNameEnding(ACTIVE_ASYNC_OPERATIONS_METRIC) should {
                                    it shouldBe 0
                                }
                            }
                        }
                    }
            } finally {
                mockServer.stop()
            }
        }

    }

}