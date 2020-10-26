package ru.fix.armeria.facade.it

import io.kotest.assertions.timing.eventually
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.time.withTimeout
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.create
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
@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlowServerAnswersIT {

    @TestFactory
    fun `WHEN there are many slow responses THEN fast responses still arrive fastly`() = DynamicTest.stream(
        listOf(
            JFixArmeriaClientPerformanceTestCaseCreator(
                testCaseName = "raw Armeria's WebClient based http client",
                clientName = "it-slow-and-fast-responses-webclient",
                testClientCreator = { (clientName, profiler) ->
                    val closeableWebClient = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .setIoThreadsCount(1)
                        .enableConnectionsProfiling(profiler)
                        .withoutRetries()
                        .setResponseTimeout(15.seconds.j)
                        .enableRequestsProfiling(profiler)
                        .buildArmeriaWebClient()
                    closeableWebClient to TestApiWebClientBasedImpl(closeableWebClient)
                }
            ),
            JFixArmeriaClientPerformanceTestCaseCreator(
                testCaseName = "raw Armeria's WebClient based http client with retries",
                clientName = "it-slow-and-fast-responses-webclient-retrying",
                expectedMetricSuffix = Metrics.EACH_RETRY_ATTEMPT_PREFIX,
                testClientCreator = { (clientName, profiler) ->
                    val closeableWebClient = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .setIoThreadsCount(1)
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
            JFixArmeriaClientPerformanceTestCaseCreator(
                testCaseName = "Artmeria Retrofit integration based client",
                clientName = "it-slow-and-fast-responses-retrofit",
                testClientCreator = { (clientName, profiler) ->
                    val closeableRetrofit = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .setIoThreadsCount(1)
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
            JFixArmeriaClientPerformanceTestCaseCreator(
                testCaseName = "Artmeria Retrofit integration based client with retries",
                clientName = "it-slow-and-fast-responses-retrofit-retrying",
                expectedMetricSuffix = Metrics.EACH_RETRY_ATTEMPT_PREFIX,
                testClientCreator = { (clientName, profiler) ->
                    val closeableRetrofit = HttpClients.builder()
                        .setClientName(clientName)
                        .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                        .setIoThreadsCount(1)
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
        JFixArmeriaClientPerformanceTestCaseCreator::testCaseName
    ) { testCaseCreator: JFixArmeriaClientPerformanceTestCaseCreator ->
        runBlocking {
            withTimeout(30.seconds.j) {
                val (clientName, expectedMetricSuffix, reporter, testApi, autoCloseableResource) = testCaseCreator()

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
                                        "remote_host" to mockServerContainer.host,
                                        "remote_port" to mockServerContainer.serverPort.toString()
                                    )
                                    it.stopSum shouldBe fastRequestsCount
                                    it.latencyMax shouldBeInRange (fastRequestDelayMs * 0.9).toLong()..
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

    }

}