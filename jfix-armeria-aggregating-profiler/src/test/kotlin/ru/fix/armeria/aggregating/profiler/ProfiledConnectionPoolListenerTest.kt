package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ConnectionPoolListener
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.timing.eventually
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.future.await
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.EPOLL_SOCKET_CHANNEL
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.indicatorWithNameEnding
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.profiledCallReportWithNameEnding
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.commons.testing.LocalHost
import java.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
internal class ProfiledConnectionPoolListenerTest {

    val mockServer = ArmeriaMockServer()

    @BeforeEach
    suspend fun beforeEach() {
        mockServer.start()
    }

    @AfterEach
    suspend fun afterEach() {
        mockServer.stop()
    }

    @Test
    suspend fun `WHEN connection created and destroyed THEN its lifetime profiled AND connections count updated`() {
        val profiler = AggregatingProfiler()
        val profilerReporter = profiler.createReporter()
        val connectionTtlMs: Long = 300
        val client = WebClient
            .builder(mockServer.httpUri())
            .factory(
                ClientFactory.builder()
                    .connectionPoolListener(
                        ProfiledConnectionPoolListener(profiler, ConnectionPoolListener.noop())
                    )
                    .idleTimeout(Duration.ofMillis(connectionTtlMs))
                    .build()
            ).build()
        mockServer.enqueue { HttpResponse.of(HttpStatus.OK) }

        val reportBeforeClientCall = profilerReporter.buildReportAndReset()
        client.get("/").aggregate().await()

        assertSoftly {
            reportBeforeClientCall.indicatorWithNameEnding(Metrics.ACTIVE_CHANNELS_COUNT) shouldBe 0
            profilerReporter.buildReportAndReset().profiledCallReportWithNameEnding(Metrics.CONNECTION_LIFETIME)
                .should {
                    shouldNotBeNull()
                    it!!.identity.tags shouldContainExactly mapOf(
                        MetricTags.REMOTE_HOST to LocalHost.HOSTNAME.value,
                        MetricTags.REMOTE_ADDRESS to LocalHost.IP.value,
                        MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                        MetricTags.PROTOCOL to "h2c",
                        MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                        MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL
                    )
                }
        }
        eventually((connectionTtlMs / 2).milliseconds) {
            profilerReporter.buildReportAndReset().indicatorWithNameEnding(Metrics.ACTIVE_CHANNELS_COUNT) shouldBe 1
        }
        eventually(3.seconds) {
            /**
             * connection destroyed and:
             * - active_channels_count indicator decreased to 0
             * - connection lifetime metric is profiled
             */
            val report = profilerReporter.buildReportAndReset()
            logger.trace { "Report: $report" }
            assertSoftly(report) {
                indicatorWithNameEnding(Metrics.ACTIVE_CHANNELS_COUNT) shouldBe 0
                profiledCallReportWithNameEnding(Metrics.CONNECTION_LIFETIME).should {
                    shouldNotBeNull()

                    it!!.latencyMax.shouldBeBetween(
                        (connectionTtlMs * 0.75).toLong(),
                        connectionTtlMs * 2
                    )
                }
            }
        }
    }

    companion object: Logging

}