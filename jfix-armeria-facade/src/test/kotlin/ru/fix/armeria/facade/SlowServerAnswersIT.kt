package ru.fix.armeria.facade

import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.Post
import com.linecorp.armeria.server.kotlin.CoroutineContextService
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.time.withTimeout
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.Test
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.create
import retrofit2.http.POST
import retrofit2.http.Query
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.commons.testing.j
import ru.fix.armeria.facade.ProfilerTestUtils.profiledCallReportWithName
import ru.fix.dynamic.property.api.DynamicProperty
import ru.fix.stdlib.concurrency.threads.NamedExecutors
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
class SlowServerAnswersIT {
    @Test
    suspend fun `WHEN there are many slow responses THEN fast responses still arrive fastly`() =
        withTimeout(30.seconds.j) {
            val clientName = "it-test-slow-and-fast-responses"
            val profiler = AggregatingProfiler()
            val reporter = profiler.createReporter()
            val mockServer = ArmeriaMockServer(mockServerName = clientName) {
                val serverDispatcher = NamedExecutors.newSingleThreadPool("armeria-mock-server-dispatcher", profiler)
                    .asCoroutineDispatcher()
                this
                    .annotatedService(TestApiAnnotatedService())
                    .requestTimeout(20.seconds.j)
                    .decorator(CoroutineContextService.newDecorator {
                        serverDispatcher + CoroutineName("armeria-mock-server-coroutine")
                    })
            }.start()
            val mockServerHost = "localhost"
            val mockServerPort = mockServer.httpPort()
            logger.info { "Mock server host=$mockServerHost; port=$mockServerPort" }

            try {
                HttpClients.builder()
                    .setClientName(clientName)
                    .setIoThreadsCount(2)
                    .setEndpoint(mockServerHost, mockServer.httpPort())
                    .setSessionProtocol(SessionProtocol.H2C)
                    .enableConnectionsProfiling(profiler)
                    .withRetriesOn503AndUnprocessedError(3)
                    .withCustomResponseTimeouts()
                    .setResponseTimeouts(
                        eachAttemptTimeout = 15.seconds.j,
                        wholeRequestTimeout = 15.seconds.j
                    )
                    .enableWholeRequestProfiling(profiler)
                    .enableRetrofitSupport()
                    .enableNamedBlockingResponseReadingExecutor(
                        DynamicProperty.of(1),
                        profiler,
                        DynamicProperty.of(10.seconds.j)
                    )
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .buildRetrofit()
                    .use { retrofit ->
                        val testApi = retrofit.retrofit.create<TestApi>()

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
                            eventually(2.seconds) {
                                val metricName = "${clientName}.${Metrics.WHOLE_RETRY_SESSION_PREFIX}.http"
                                val report = reporter.buildReportAndReset { metric, _ ->
                                    metric.name == metricName
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
                mockServer.stop()
            }
        }

    companion object : Logging {

        interface TestApi {
            @POST("/delayed")
            suspend fun delayedAnswer(
                @Query("delayMs") delayMs: Long,
                @Query("jitter") jitter: Long = 0L
            ): String
        }

        class TestApiAnnotatedService : TestApi {

            @Post("/delayed")
            override suspend fun delayedAnswer(
                @Param("delayMs") delayMs: Long,
                @Param("jitter") jitter: Long
            ): String {
                require(jitter < delayMs)
                delay(delayMs + ThreadLocalRandom.current().nextLong(-jitter, jitter))
                return "Response delayed for $delayMs ms"
            }

        }

    }
}