package ru.fix.armeria.limiter

import com.linecorp.armeria.client.DecoratingHttpClientFunction
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.testing.junit.server.mock.MockWebServerExtension
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asDeferred
import mu.KLogging
import org.awaitility.Duration.ONE_SECOND
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.has
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import retrofit2.create
import retrofit2.http.GET
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.aggregating.profiler.Identity
import ru.fix.aggregating.profiler.Profiler
import ru.fix.aggregating.profiler.ProfilerReport
import ru.fix.dynamic.property.api.DynamicProperty
import ru.fix.stdlib.concurrency.threads.NamedExecutors
import ru.fix.stdlib.ratelimiter.ConfigurableRateLimiter
import ru.fix.stdlib.ratelimiter.RateLimitedDispatcher
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

private typealias MockServerCreatorResult = Pair<AutoCloseable, URI>
private typealias MockServerCreator = (Int) -> MockServerCreatorResult
private typealias ClientCreator<ClientT> = (URI, RateLimitedDispatcher, Profiler) -> ClientT
private typealias ClientWithProfiledCallCreator<ClientT>
        = (DecoratingHttpClientFunction, URI, RateLimitedDispatcher, Profiler) -> ClientT

private typealias ClientToServerLoadEmulator<ClientT> = suspend (Int, ClientT) -> Unit

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
    @Disabled("""
Disabled due to bug in armeria (TODO provide issue to armeria).
When ArmeriaRetrofit with streaming(true) option used, then com.linecorp.armeria.common.Response#whenComplete 
callback is not executed.
""")
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
                    MockServerCreatorResult(it.start(), it.httpUri())
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
    @Disabled("""
Disabled due to bug in armeria (TODO provide issue to armeria).
When ArmeriaRetrofit with streaming(true) option used, then com.linecorp.armeria.common.Response#whenComplete 
callback is not executed.
""")
    suspend fun `RPS restricted by rate limiter dispatcher for 'streaming retrofit'`() {
        `emulate load to server with specified client and check throughput`<TestClient>(
            rateLimiterName = "retrofit-streaming-client-limiter",
            targetPermitsPerSec = 100,
            windowProperty = DynamicProperty.of(200),
            requestsCount = 400,
            mockServerCreator = { requestsCount ->
                mockServerWithResponsesDelayedByIndexMod(requestsCount).let {
                    MockServerCreatorResult(it.start(), it.httpUri())
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
                    MockServerCreatorResult(it.start(), it.httpUri())
                }
            },
            createHttpClientWithProfiledCall = { testProfiledCallDecorator, uri, rateLimitedDispatcher, profiler ->
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

    companion object : KLogging() {

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
        ): MockWebServerExtension = MockWebServerExtension().apply {
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
        ): ProfilerReport {
            val targetPermitsPerSecDouble = targetPermitsPerSec.toDouble()
            val (autoCloseable, mockServerUri) = mockServerCreator(requestsCount)
            autoCloseable.use {
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
                        val report = reporter.buildReportAndReset()
                        logger.info { "Report: $report" }

                        assertSoftly(report) {
                            val testMetricCallReport =
                                profilerCallReports.single { it.identity.name.endsWith(TEST_METRIC_NAME) }
                            testMetricCallReport.startThroughputAvg.shouldBeBetween(
                                targetPermitsPerSecDouble,
                                targetPermitsPerSecDouble,
                                targetPermitsPerSecDouble * 0.10
                            )
                            indicators.indicatorWithNameEnding(ACTIVE_ASYNC_OPERATIONS_METRIC) shouldBe 0
                        }
                        return report
                    }
            }
        }

        private suspend fun <ClientT> `request completion releases resources`(
            rateLimiterName: String,
            clientCreator: ClientCreator<ClientT>,
            execute: ClientT.() -> Deferred<*>
        ) {
            val responseFuture = CompletableFuture<HttpResponse>()
            val mockServer = MockWebServerExtension().apply {
                enqueue(HttpResponse.from(responseFuture))
            }
            mockServer.start().use {
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
                        delay(200)
                        await atMost ONE_SECOND untilCallTo {
                            profilerReporter.buildReportAndReset().indicators
                        } has {
                            indicatorWithNameEnding(ACTIVE_ASYNC_OPERATIONS_METRIC) == 1L
                        }
                        responseFuture.complete(HttpResponse.of(HttpStatus.OK))
                        requestFuture.await()
                        await atMost ONE_SECOND untilCallTo {
                            profilerReporter.buildReportAndReset().indicators
                        } has {
                            indicatorWithNameEnding(ACTIVE_ASYNC_OPERATIONS_METRIC) == 0L
                        }
                    }
            }
        }

    }

}