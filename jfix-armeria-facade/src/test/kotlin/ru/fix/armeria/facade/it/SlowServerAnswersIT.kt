package ru.fix.armeria.facade.it

import com.linecorp.armeria.client.WebClient
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.time.withTimeout
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.create
import retrofit2.http.GET
import retrofit2.http.Query
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.aggregating.profiler.Profiler
import ru.fix.aggregating.profiler.ProfilerReporter
import ru.fix.armeria.commons.testing.IntegrationTest
import ru.fix.armeria.commons.testing.j
import ru.fix.armeria.facade.HttpClients
import ru.fix.armeria.facade.Metrics
import ru.fix.armeria.facade.ProfilerTestUtils.profiledCallReportWithName
import ru.fix.dynamic.property.api.DynamicProperty
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
@IntegrationTest
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlowServerAnswersIT {

    @TestFactory
    fun `WHEN there are many slow responses THEN fast responses still arrive fastly`() = DynamicTest.stream(
        listOf(
            ManySlowAndFastResponsesTestCase(
                testCaseName = "raw Armeria's WebClient based http client",
                clientName = "it-slow-and-fast-responses-webclient",
                testClientCreator = { (clientName, profiler) ->
                    val closeableWebClient = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .enableConnectionsProfiling(profiler)
                        .withoutRetries()
                        .setResponseTimeout(15.seconds.j)
                        .enableRequestsProfiling(profiler)
                        .buildArmeriaWebClient()
                    closeableWebClient to TestApiWebClientBasedImpl(closeableWebClient)
                }
            ),
            ManySlowAndFastResponsesTestCase(
                testCaseName = "raw Armeria's WebClient based http client with retries",
                clientName = "it-slow-and-fast-responses-webclient-retrying",
                expectedMetricSuffix = Metrics.EACH_RETRY_ATTEMPT_PREFIX,
                testClientCreator = { (clientName, profiler) ->
                    val closeableWebClient = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .enableConnectionsProfiling(profiler)
                        .withRetriesOn503AndUnprocessedError(3)
                        .withCustomResponseTimeouts()
                        .setResponseTimeouts(
                            eachAttemptTimeout = 15.seconds.j,
                            wholeRequestTimeout = 15.seconds.j
                        )
                        .enableEachAttemptProfiling(profiler)
                        .buildArmeriaWebClient()
                    closeableWebClient to TestApiWebClientBasedImpl(closeableWebClient)
                }
            ),
            ManySlowAndFastResponsesTestCase(
                testCaseName = "Artmeria Retrofit integration based client",
                clientName = "it-slow-and-fast-responses-retrofit",
                testClientCreator = { (clientName, profiler) ->
                    val closeableRetrofit = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .enableConnectionsProfiling(profiler)
                        .withoutRetries()
                        .setResponseTimeout(15.seconds.j)
                        .enableRequestsProfiling(profiler)
                        .enableRetrofitSupport()
                        .enableNamedBlockingResponseReadingExecutor(
                            DynamicProperty.of(1),
                            profiler,
                            DynamicProperty.of(10.seconds.j)
                        )
                        .addConverterFactory(ScalarsConverterFactory.create())
                        .buildRetrofit()
                    closeableRetrofit to closeableRetrofit.retrofit.create()
                }
            ),
            ManySlowAndFastResponsesTestCase(
                testCaseName = "Artmeria Retrofit integration based client with retries",
                clientName = "it-slow-and-fast-responses-retrofit-retrying",
                expectedMetricSuffix = Metrics.EACH_RETRY_ATTEMPT_PREFIX,
                testClientCreator = { (clientName, profiler) ->
                    val closeableRetrofit = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .enableConnectionsProfiling(profiler)
                        .withRetriesOn503AndUnprocessedError(3)
                        .withCustomResponseTimeouts()
                        .setResponseTimeouts(
                            eachAttemptTimeout = 15.seconds.j,
                            wholeRequestTimeout = 15.seconds.j
                        )
                        .enableEachAttemptProfiling(profiler)
                        .enableRetrofitSupport()
                        .enableNamedBlockingResponseReadingExecutor(
                            DynamicProperty.of(1),
                            profiler,
                            DynamicProperty.of(10.seconds.j)
                        )
                        .addConverterFactory(ScalarsConverterFactory.create())
                        .buildRetrofit()
                    closeableRetrofit to closeableRetrofit.retrofit.create()
                }
            )
        ).iterator(),
        ManySlowAndFastResponsesTestCase::testCaseName
    ) { testCase: ManySlowAndFastResponsesTestCase ->
        runBlocking {
            withTimeout(30.seconds.j) {
                val (clientName, expectedMetricSuffix, reporter, testApi, autoCloseableResource) = testCase()
                val mockServer = mockServerContainer
                val mockServerHost = mockServer.host
                val mockServerPort = mockServer.serverPort

                try {
                    autoCloseableResource.use {

                        logger.info {
                            "Warming up client and server to discard first requests' resources initialization..."
                        }
                        (1..1000).map {
                            launch {
                                testApi.delayedAnswer(100, 30)
                            }
                        }.joinAll()

                        val slowRequestsCount = 1000
                        val slowRequestDelay = 10.seconds
                        val slowRequestDelayMs = slowRequestDelay.toLongMilliseconds()
                        logger.info { "Submitting $slowRequestsCount slow requests with delay $slowRequestDelay..." }
                        val slowDeferredResults = (1..slowRequestsCount).map {
                            async {
                                testApi.delayedAnswer(slowRequestDelayMs, 50)
                            }
                        }

                        try {
                            val fastRequestsCount = 100
                            val fastRequestDelay = 500.milliseconds
                            val fastRequestDelayMs = fastRequestDelay.toLongMilliseconds()
                            logger.info { "Performing $fastRequestsCount fast requests with delay $fastRequestDelay..." }
                            (1..fastRequestsCount).map {
                                async {
                                    testApi.delayedAnswer(fastRequestDelayMs, 10)
                                }
                            }.awaitAll()

                            logger.info { "Checking metrics..." }
                            delay(100)
                            eventually(2.seconds) {
                                val metricName = "${clientName}${expectedMetricSuffix?.let { ".$it" } ?: ""}.http"
                                val report = reporter.buildReportAndReset { metric, _ ->
                                    metric.name == metricName
                                            && metric.tags["path"]?.contains("delayMs=$fastRequestDelayMs")
                                            ?: false
                                }
                                logger.trace { "Report: $report" }
                                report.profiledCallReportWithName(metricName).should {
                                    it.shouldNotBeNull()

                                    it.identity.tags shouldContainAll mapOf(
                                        "remote_host" to mockServerHost,
                                        "remote_port" to mockServerPort.toString()
                                    )
                                    it.stopSum shouldBe fastRequestsCount
                                    it.latencyAvg shouldBeInRange (fastRequestDelayMs * 0.5).toLong()..
                                            (fastRequestDelayMs * 2)
                                }
                            }
                        } finally {
                            slowDeferredResults.awaitAll()
                        }
                    }
                } finally {
                    logger.info { "Final report: ${reporter.buildReportAndReset()}" }
                }
            }
        }
    }

    companion object : Logging {

        val mockServerContainer = JFixTestWebfluxServerContainer

        interface TestApi {

            companion object {
                const val DELAYED_ANSWER_PATH = "${JFixTestWebfluxServerContainer.basePath}/delayedAnswer"
            }

            @GET(DELAYED_ANSWER_PATH)
            suspend fun delayedAnswer(
                @Query("delayMs") delayMs: Long,
                @Query("jitter") jitter: Long? = null
            ): String
        }

        class TestApiWebClientBasedImpl(private val webClient: WebClient) : TestApi {

            override suspend fun delayedAnswer(delayMs: Long, jitter: Long?): String {
                val response = webClient.get(
                    "${TestApi.DELAYED_ANSWER_PATH}?delayMs=${delayMs}${jitter?.let { "&jitter=${it}" } ?: ""}"
                ).aggregate().await()
                return response.contentUtf8()
            }

        }

        data class ManySlowAndFastResponsesTestCase(
            val testCaseName: String,
            private val clientName: String,
            private val expectedMetricSuffix: String? = null,
            private val testClientCreator: (CreatorInput) -> Pair<AutoCloseable, TestApi>
        ) : () -> ManySlowAndFastResponsesTestCase.Data {

            data class CreatorInput(
                val clientName: String,
                val profiler: Profiler
            )

            data class Data(
                val clientName: String,
                val expectedMetricSuffix: String?,
                val reporter: ProfilerReporter,
                val testApi: TestApi,
                val autoCloseableResource: AutoCloseable
            )

            override fun invoke(): Data {
                val profiler = AggregatingProfiler()
                val reporter = profiler.createReporter()
                val (autoCloseable, testApi) = testClientCreator(CreatorInput(clientName, profiler))
                return Data(clientName, expectedMetricSuffix, reporter, testApi, autoCloseable)
            }
        }

    }
}