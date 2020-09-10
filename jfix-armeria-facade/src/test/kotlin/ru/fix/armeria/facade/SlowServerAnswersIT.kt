package ru.fix.armeria.facade

import com.linecorp.armeria.common.SessionProtocol
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.model.Delay
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import retrofit2.create
import retrofit2.http.POST
import retrofit2.http.Query
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.armeria.commons.testing.j
import ru.fix.armeria.facade.ProfilerTestUtils.profiledCallReportWithName
import ru.fix.dynamic.property.api.DynamicProperty
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
class SlowServerAnswersIT {
    @Test
    suspend fun `WHEN there are many slow responses THEN fast responses still arrive fastly`() /*= runBlocking<Unit>*/ {
        val mockServer = MockServerContainer/*.container*/

        MockServerClient(mockServer.host, mockServer.serverPort)
            .`when`(request()
                .withPath("/delayed")
                .withMethod("POST")
            ).respond {
                val delayMs = it.getFirstQueryStringParameter("delayMs").toLong()
                response()
                    .withDelay(Delay.milliseconds(delayMs))
                    .withBody("Response delayed for $delayMs ms")
                    .withStatusCode(200)
            }
        val profiler = AggregatingProfiler()
        val reporter = profiler.createReporter()
        val clientName = "it-test-slow-and-fast-responses"
        HttpClients.builder()
            .setClientName(clientName)
            .setEndpoint(mockServer.host, mockServer.serverPort)
            .setSessionProtocol(SessionProtocol.H2C)
            .withRetriesOn503AndUnprocessedError(3)
            .withCustomResponseTimeouts()
            .setEachAttemptResponseTimeout(30.seconds.j)
            .enableEachAttemptProfiling(profiler)
            .enableRetrofitSupport()
            .enableNamedBlockingResponseReadingExecutor(
                DynamicProperty.of(2),
                profiler,
                DynamicProperty.of(10.seconds.j)
            )
            .buildRetrofit().use { retrofit ->
                val testApi = retrofit.retrofit.create<TestApi>()

                val slowDeferredResults = (1..10000).map {
                    GlobalScope.async {
                        testApi.delayedAnswer(15.seconds.toLongMilliseconds())
                    }
                }

                val fastRequestsCount = 100
                val fastRequestDelayMs = 100.milliseconds.toLongMilliseconds()
                (1..fastRequestsCount).map {
                    GlobalScope.async {
                        testApi.delayedAnswer(fastRequestDelayMs)
                    }
                }.awaitAll()

                eventually(2.seconds) {
                    val metricName = "${clientName}.${Metrics.EACH_RETRY_ATTEMPT_PREFIX}.http"
                    val report = reporter.buildReportAndReset { metric, _ ->
                        metric.name == metricName
                    }
                    logger.trace { "Report: $report" }
                    report.profiledCallReportWithName(metricName).should {
                        it.shouldNotBeNull()

                        it.identity.tags shouldContainAll mapOf(
                            "remote_host" to mockServer.host,
                            "remote_port" to mockServer.serverPort.toString()
                        )
                        it.stopSum shouldBe fastRequestsCount
                        it.latencyAvg shouldBeInRange (fastRequestDelayMs * 0.8).toLong()..
                                (fastRequestDelayMs * 1.2).toLong()
                    }
                }
                slowDeferredResults.awaitAll()
            }
    }

    companion object : Logging {

//        @Container
//        private val mockServer = MockServerContainer("5.11.1")

        interface TestApi {
            @POST("/delayed")
            suspend fun delayedAnswer(@Query("delayMs") delayMs: Long): String
        }
    }
}