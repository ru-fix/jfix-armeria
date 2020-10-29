package ru.fix.armeria.facade.it

import io.kotest.assertions.timing.eventually
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.time.withTimeout
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.*
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
import kotlin.time.minutes
import kotlin.time.seconds

@ExperimentalTime
@IntegrationTest
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UnstableServerResponsesIT {

    @Order(1)
    @TestFactory
    fun `WHEN there are big responses and bad bandwidth THEN they still arrive at expected time according to their size and bandwidth value`() =
        DynamicTest.stream(
            listOf(
                JFixArmeriaClientPerformanceTestCaseCreator(
                    testCaseName = "raw Armeria's WebClient based http client with retries",
                    clientName = "it-fat-low-bandwidth-responses-webclient-retrying",
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
                                eachAttemptTimeout = 2.minutes.j,
                                wholeRequestTimeout = 2.minutes.j
                            )
                            .enableEachAttemptProfiling(profiler)
                            .buildArmeriaWebClient()
                        closeableWebClient to TestApiWebClientBasedImpl(closeableWebClient)
                    }
                )
                /**
                 * WHY IT IS DISABLED? due to presence of blocking response reading executor,
                 * concurrency of fat response reading is proportional to size of blocking executor thread
                 */
                //,
                /*JFixArmeriaClientPerformanceTestCaseCreator(
                    testCaseName = "Artmeria Retrofit integration based client",
                    clientName = "it-fat-low-bandwidth-responses-retrofit",
                    testClientCreator = { (clientName, profiler) ->
                        val closeableRetrofit = HttpClients.builder()
                            .setClientName(clientName)
                            .setEndpoint(mockServerContainer.host, mockServerContainer.serverPort)
                            .setIoThreadsCount(1)
                            .enableConnectionsProfiling(profiler)
                            .withoutRetries()
                            .setResponseTimeout(2.minutes.j)
                            .enableRequestsProfiling(profiler)
                            .enableRetrofitSupport()
                            .enableNamedBlockingResponseReadingExecutor(
                                DynamicProperty.of(1),
                                profiler,
                                DynamicProperty.of(10.seconds.j)
                            )
                            .buildRetrofit()
                        closeableRetrofit to closeableRetrofit.retrofit.create()
                    }
                )*/
            ).iterator(),
            JFixArmeriaClientPerformanceTestCaseCreator::testCaseName
        ) { testCaseCreator ->
            runBlocking {
                val (clientName, expectedMetricSuffix, reporter, testApi, autoCloseableResource) = testCaseCreator()
                try {
                    autoCloseableResource.use {

                        val requestsCount = 1_000
                        val bandwidthInKbPerSec = 10
                        val expectedDelay = (ONE_MB_IN_BYTES / (bandwidthInKbPerSec * ONE_KB_IN_BYTES)).seconds
                        val expectedDelayMs = expectedDelay.toLongMilliseconds()
                        val responsePartSizeInBytes = bandwidthInKbPerSec * ONE_KB_IN_BYTES / 10
                        val responsePartsCount = ONE_MB_IN_BYTES / responsePartSizeInBytes
                        val delayBetweenResponsePartsMs = 1.seconds.toLongMilliseconds() / 10
                        logger.info { """Launching $requestsCount requests for 1 megabyte responses with:
                            | - $bandwidthInKbPerSec kb/sec bandwidth
                            | - $expectedDelay expected delay
                            | - response part size of $responsePartSizeInBytes bytes
                            | - $responsePartsCount response parts
                            | - ${delayBetweenResponsePartsMs}ms delay between response parts
                            | """.trimMargin() }
                        (1..requestsCount).map {
                            launch {
                                testApi.delayedParts(
                                    partsCount = responsePartsCount,
                                    partSizeInBytes = responsePartSizeInBytes,
                                    delayBetweenPartsMs = delayBetweenResponsePartsMs
                                )
                            }
                        }.joinAll()

                        logger.info { "Checking metrics..." }
                        delay(100)
                        eventually(2.seconds) {
                            val metricName = "${clientName}${expectedMetricSuffix?.let { ".$it" } ?: ""}.http"
                            val report = reporter.buildReportAndReset { metric, _ ->
                                metric.name == metricName
                            }
                            logger.trace { "Report: $report" }
                            report.profiledCallReportWithName(metricName).should {
                                it.shouldNotBeNull()

                                it.stopSum shouldBe requestsCount
                                it.latencyMax shouldBeInRange (expectedDelayMs * 0.9).toLong()..
                                        (expectedDelayMs * 1.1).toLong()
                            }
                        }
                    }
                } finally {
                    logger.info { "Final report: ${reporter.buildReportAndReset()}" }
                }
            }
        }

    @Order(2)
    @TestFactory
    fun `WHEN there are many slow responses THEN fast responses still arrive fastly`() = DynamicTest.stream(
        listOf(
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
                                testApi.delayedAnswer(slowRequestDelayMs, 1000)
                            }
                        }

                        try {
                            val fastRequestsCount = 100
                            val fastRequestDelay = 500.milliseconds
                            val fastRequestDelayMs = fastRequestDelay.toLongMilliseconds()
                            logger.info { "Performing $fastRequestsCount fast requests with delay $fastRequestDelay..." }
                            (1..fastRequestsCount).map {
                                async {
                                    testApi.delayedAnswer(fastRequestDelayMs, 50)
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

        const val ONE_KB_IN_BYTES = 1000
        const val ONE_MB_IN_BYTES = ONE_KB_IN_BYTES * 1000

        val mockServerContainer = JFixTestWebfluxServerContainer

    }
}