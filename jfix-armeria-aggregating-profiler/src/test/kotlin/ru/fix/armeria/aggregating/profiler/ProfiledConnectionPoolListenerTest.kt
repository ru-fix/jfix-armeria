package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ConnectionPoolListener
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.testing.junit.server.mock.MockWebServerExtension
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import ru.fix.aggregating.profiler.Identity
import ru.fix.aggregating.profiler.IndicationProvider
import ru.fix.aggregating.profiler.NoopProfiler
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.EPOLL_SOCKET_CHANNEL
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.LOCAL_ADDRESS
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.LOCAL_HOST
import ru.fix.armeria.aggregating.profiler.ProfilerTestUtils.localhostHttpUri
import java.time.Duration

internal class ProfiledConnectionPoolListenerTest {

    companion object {

        @JvmField
        @RegisterExtension
        val mockServer = MockWebServerExtension()
    }

    @Test
    fun `WHEN connection created and destryed THEN its lifetime profiled AND connections count updated`() {
        var startTimestamp: Long? = null
        var stopTimestamp: Long? = null
        val connectionCountIndicatorSlot = slot<IndicationProvider>()
        val mockedConnectionLifetimeCall = spyk(NoopProfiler.NoopProfiledCall()) {
            every {
                start()
            } answers {
                startTimestamp = System.currentTimeMillis()
                callOriginal()
            }
            every {
                stop()
            } answers {
                stopTimestamp = System.currentTimeMillis()
                callOriginal()
            }
        }
        val mockedProfiler = spyk(NoopProfiler()) {
            every {
                attachIndicator(any<String>(), capture(connectionCountIndicatorSlot))
            } answers {
                callOriginal()
            }
            excludeRecords { attachIndicator(any<String>(), any()) }
            every {
                profiledCall(match<Identity> { it.name == Metrics.CONNECTION_LIFETIME })
            } returns mockedConnectionLifetimeCall
        }
        val connectionTtl: Long = 500
        val client = WebClient
            .builder(mockServer.localhostHttpUri())
            .factory(
                ClientFactory.builder()
                    .connectionPoolListener(
                        ProfiledConnectionPoolListener(mockedProfiler, ConnectionPoolListener.noop())
                    )
                    .idleTimeout(Duration.ofMillis(connectionTtl))
                    .build()
            ).build()
        mockServer.enqueue(HttpResponse.of(HttpStatus.OK))
        val connectionLifetimeMetricSlot = slot<Identity>()

        val connectionsIndicatorValueBeforeClientCall = connectionCountIndicatorSlot.captured.get()
        client.get("/").aggregate().join()
        val connectionsIndicatorValueAfterClientCall = connectionCountIndicatorSlot.captured.get()

        verify {
            mockedProfiler.profiledCall(
                capture(connectionLifetimeMetricSlot)
            )
            mockedConnectionLifetimeCall.start()
        }
        verify(timeout = connectionTtl) {
            mockedConnectionLifetimeCall.stop()
        }
        val connectionsIndicatorValueAfterTtlExpired = connectionCountIndicatorSlot.captured.get()
        confirmVerified(mockedProfiler, mockedConnectionLifetimeCall)
        assertSoftly {
            connectionLifetimeMetricSlot.captured.assertSoftly {
                it.name shouldBe Metrics.CONNECTION_LIFETIME
                it.tags shouldContainExactly mapOf(
                    MetricTags.REMOTE_HOST to LOCAL_HOST,
                    MetricTags.REMOTE_ADDRESS to LOCAL_ADDRESS,
                    MetricTags.REMOTE_PORT to mockServer.httpPort().toString(),
                    MetricTags.PROTOCOL to "h2c",
                    MetricTags.IS_MULTIPLEX_PROTOCOL to true.toString(),
                    MetricTags.CHANNEL_CLASS to EPOLL_SOCKET_CHANNEL
                )
            }
            (stopTimestamp!! - startTimestamp!!) shouldBeGreaterThanOrEqual 500
            connectionsIndicatorValueBeforeClientCall shouldBe 0
            connectionsIndicatorValueAfterClientCall shouldBe 1
            connectionsIndicatorValueAfterTtlExpired shouldBe 0
        }
    }

}