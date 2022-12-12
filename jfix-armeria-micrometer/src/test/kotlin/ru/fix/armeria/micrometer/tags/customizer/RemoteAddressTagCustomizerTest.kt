package ru.fix.armeria.micrometer.tags.customizer

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.metric.MetricCollectingClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.server.annotation.Post
import io.kotest.assertions.timing.eventually
import io.kotest.inspectors.forOne
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.logging.LoggingMeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.commons.testing.j
import ru.fix.armeria.micrometer.tags.MetricTags
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

@OptIn(ExperimentalTime::class)
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteAddressTagCustomizerTest {

    companion object : Logging {
        private val clientFactory = ClientFactory.ofDefault()
    }

    private lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun setUp() {
        meterRegistry = CompositeMeterRegistry(
            Clock.SYSTEM,
            listOf(
                LoggingMeterRegistry(),
                SimpleMeterRegistry()
            )
        )
        clientFactory.apply {
            setMeterRegistry(meterRegistry)
        }
    }

    @AfterEach
    fun tearDown() {
        meterRegistry.close()
    }

    @Test
    fun `WHEN query string contains query parameters THEN they removed from 'path' metric tag`(): Unit = runBlocking {
        val path = "/testPath"
        ArmeriaMockServer {
            annotatedService(object : Any() {
                @Post("/testPath")
                fun testPath(): HttpResponse {
                    return HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.OK),
                        50.toDuration(TimeUnit.MILLISECONDS).j
                    )
                }
            })
        }.use { mockServer ->
            val metricsPrefix = "test.remote.address.tag"

            val client = WebClient
                .builder(mockServer.httpUri())
                .factory(clientFactory)
                .decorator(
                    MetricCollectingClient.newDecorator(
                        MeterIdPrefixFunction.ofDefault(metricsPrefix)
                            .andThen(RemoteAddressTagCustomizer)
                    )
                )
                .build()

            client.post(path, HttpData.empty()).aggregate().await()

            eventually(5.toDuration(TimeUnit.SECONDS)) {
                val totalDurationMetricName = "$metricsPrefix.total.duration"
                val search = meterRegistry.find(totalDurationMetricName)

                search.meter() should {
                    it.shouldNotBeNull()

                    it.id.name shouldBe totalDurationMetricName
                    it.id.tags.forOne { tag ->
                        tag.key shouldBe MetricTags.REMOTE_ADDRESS
                        tag.value shouldMatch """\d+\.\d+\.\d+\.\d+"""
                    }
                }
            }
        }
    }
}