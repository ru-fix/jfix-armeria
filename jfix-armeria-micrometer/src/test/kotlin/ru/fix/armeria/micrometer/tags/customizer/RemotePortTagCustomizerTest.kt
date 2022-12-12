package ru.fix.armeria.micrometer.tags.customizer

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.client.metric.MetricCollectingClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.Put
import io.kotest.assertions.timing.eventually
import io.kotest.inspectors.forAll
import io.kotest.inspectors.forOne
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
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
class RemotePortTagCustomizerTest {

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
        val testPath = "/testPath"
        val testPathMockServerCustomizer: ServerBuilder.() -> ServerBuilder = {
            annotatedService(object : Any() {
                @Put("/testPath")
                fun testPath(): HttpResponse {
                    return HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.OK),
                        50.toDuration(TimeUnit.MILLISECONDS).j
                    )
                }
            })
        }
        ArmeriaMockServer(serverBuilderCustomizer = testPathMockServerCustomizer).use { mockServer1 ->
            ArmeriaMockServer(serverBuilderCustomizer = testPathMockServerCustomizer).use { mockServer2 ->
                val metricsPrefix = "test.remote.port.tag"

                val client = WebClient
                    .builder(
                        SessionProtocol.HTTP,
                        EndpointGroup.of(
                            EndpointSelectionStrategy.roundRobin(),
                            mockServer1.httpEndpoint(),
                            mockServer2.httpEndpoint()
                        )
                    )
                    .factory(clientFactory)
                    .decorator(
                        MetricCollectingClient.newDecorator(
                            MeterIdPrefixFunction.ofDefault(metricsPrefix)
                                .andThen(RemotePortTagCustomizer)
                        )
                    )
                    .build()

                client.put(testPath, HttpData.empty()).aggregate().await()
                client.put(testPath, HttpData.empty()).aggregate().await()

                eventually(5.toDuration(TimeUnit.SECONDS)) {
                    val totalDurationMetricName = "$metricsPrefix.total.duration"
                    val search = meterRegistry.find(totalDurationMetricName)

                    search.meters() should { meters ->
                        meters shouldHaveSize 2
                        meters.forAll { meter ->
                            meter.id.name shouldBe totalDurationMetricName
                            meter.id.tags.forOne {
                                it.key shouldBe MetricTags.REMOTE_PORT
                            }
                        }
                        meters.forOne { meter ->
                            val remotePortTagValue = meter.id.getTag(MetricTags.REMOTE_PORT)!!

                            remotePortTagValue shouldBe mockServer1.httpPort().toString()
                        }
                        meters.forOne { meter ->
                            val remotePortTagValue = meter.id.getTag(MetricTags.REMOTE_PORT)!!

                            remotePortTagValue shouldBe mockServer2.httpPort().toString()
                        }
                    }
                }
            }
        }
    }
}