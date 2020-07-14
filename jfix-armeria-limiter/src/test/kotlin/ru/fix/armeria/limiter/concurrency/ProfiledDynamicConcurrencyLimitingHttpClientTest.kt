package ru.fix.armeria.limiter.concurrency

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.testing.junit.server.mock.MockWebServerExtension
import io.mockk.spyk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import ru.fix.aggregating.profiler.NoopProfiler
import ru.fix.dynamic.property.api.DynamicProperty

internal class ProfiledDynamicConcurrencyLimitingHttpClientTest {

    @Test
    @DisplayName(
        "WHEN max concurrency specified AND wait timeout is not " +
                "THEN concurrency limited " +
                "AND queue waiting not limited " +
                "AND waiting profiled" +
                "AND pending requests count profiled " +
                "AND active requests count profiled"
    )
    fun `max concurrency specified but wait timeout is not `() {
        val mockedProfiler = spyk(NoopProfiler()) {}
        WebClient.builder(mockServer.httpUri())
            .decorator(
                ProfiledDynamicConcurrencyLimitingHttpClient.newDecorator(
                    mockedProfiler,
                    DynamicProperty.of(1),
                    DynamicProperty.of(-1)
                )
            )
    }

    @Test
    @DisplayName(
        "WHEN max concurrency specified AND wait timeout is also " +
                "THEN concurrency limited " +
                "AND queue waiting limited by specified timeout " +
                "AND waiting profiled " +
                "AND timeout occurrence profiled" +
                "AND pending requests count profiled " +
                "AND active requests count profiled"
    )
    fun `max concurrency and wait timeout are specified `() {
        TODO("Not yet implemented")
    }

    @Test
    @DisplayName(
        "WHEN max concurrency is not specified " +
                "THEN concurrency is not limited " +
                "AND active requests count profiled"
    )
    internal fun `max concurrency is not specified`() {
        TODO("Not yet implemented")
    }

    @Nested
    inner class `WHEN retry decorator placed` {

        @Test
        fun `before profiled one THEN `() {
            TODO()
        }

        @Test
        fun `after profiled one AND  THEN 1`() {
            TODO()
        }

        @Test
        fun `after profiled one AND  THEN 2`() {
            TODO()
        }

    }

    companion object {
        @JvmField
        @RegisterExtension
        val mockServer = MockWebServerExtension()
    }
}