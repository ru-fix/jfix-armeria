package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ConnectionPoolListener
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.SessionProtocol
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.future.await
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.*
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.EPOLL_SOCKET_CHANNEL
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.indicatorWithNameEnding
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.profiledCallReportWithNameEnding
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.commons.testing.LocalHost
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

@ExperimentalTime
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ProfiledConnectionPoolListenerTest {

    private val mockServer = ArmeriaMockServer()

    @BeforeAll
    suspend fun beforeAll() {
        mockServer.start()
    }

    @AfterAll
    suspend fun afterAll() {
        mockServer.stop()
    }

    @Test
    suspend fun `WHEN http_1_1 connection created and destroyed THEN its lifetime profiled AND connections count updated`() {
        `WHEN connection created and destroyed THEN its lifetime profiled AND connections count updated`(SessionProtocol.H1C)
    }

    @Test
    suspend fun `WHEN http_2 connection created and destroyed THEN its lifetime profiled AND connections count updated`() {
        `WHEN connection created and destroyed THEN its lifetime profiled AND connections count updated`(SessionProtocol.H2C)
    }

    private suspend fun `WHEN connection created and destroyed THEN its lifetime profiled AND connections count updated`(
        sessionProtocol: SessionProtocol
    ) {
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        try {
            val connectionTtlMs: Long = 1.toDuration(TimeUnit.SECONDS).inWholeMilliseconds
            val client = WebClient
                .builder(mockServer.uri(sessionProtocol))
                .factory(
                    ClientFactory.builder()
                        .connectionPoolListener(
                            ProfiledConnectionPoolListener(profiler, ConnectionPoolListener.noop())
                        )
                        .idleTimeout(Duration.ofMillis(connectionTtlMs))
                        .build()
                ).build()
            mockServer.enqueue { HttpResponse.of(HttpStatus.OK) }

            val reportBeforeClientCall = profilerReporter.buildReportAndReset { metric, _ ->
                metric.name.endsWith(Metrics.ACTIVE_CHANNELS_COUNT)
            }
            client.get("/").aggregate().await()

            assertSoftly {
                reportBeforeClientCall.indicatorWithNameEnding(Metrics.ACTIVE_CHANNELS_COUNT) shouldBe 0
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name.endsWith(Metrics.CONNECTION_LIFETIME)
                }
                logger.trace { "$sessionProtocol Report: $report" }
                report.profiledCallReportWithNameEnding(Metrics.CONNECTION_LIFETIME)
                    .should {
                        it.shouldNotBeNull()
                        it.identity.tags shouldContainExactly mapOf(
                            MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                            MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                            MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                            MetricTags.PROTOCOL to sessionProtocol.uriText(),
                            MetricTags.IS_MULTIPLEX_PROTOCOL to sessionProtocol.isMultiplex.toString(),
                            MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL
                        )
                    }
            }
            eventually((connectionTtlMs / 2).toDuration(TimeUnit.MILLISECONDS)) {
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name.endsWith(Metrics.ACTIVE_CHANNELS_COUNT)
                }
                logger.trace { "$sessionProtocol Report: $report" }
                report.indicatorWithNameEnding(Metrics.ACTIVE_CHANNELS_COUNT) shouldBe 1
            }
            /**
             * connection destroyed and:
             * - active_channels_count indicator decreased to 0
             * - connection lifetime metric is profiled
             */
            eventually(2.toDuration(TimeUnit.SECONDS)) {
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name.endsWith(Metrics.ACTIVE_CHANNELS_COUNT)
                }
                logger.trace { "$sessionProtocol Report: $report" }
                report.indicatorWithNameEnding(Metrics.ACTIVE_CHANNELS_COUNT) shouldBe 0
            }
            eventually(1.toDuration(TimeUnit.SECONDS)) {
                val report = profilerReporter.buildReportAndReset { metric, _ ->
                    metric.name.endsWith(Metrics.CONNECTION_LIFETIME)
                }
                logger.trace { "$sessionProtocol Report: $report" }
                report.profiledCallReportWithNameEnding(Metrics.CONNECTION_LIFETIME).should {
                    it.shouldNotBeNull()

                    it.latencyMax.shouldBeBetween(
                        (connectionTtlMs * 0.75).toLong(),
                        connectionTtlMs * 3
                    )
                }
            }
        } finally {
            logger.trace { "$sessionProtocol Final report: ${profilerReporter.buildReportAndReset()}" }
        }
    }

    companion object: Logging

}