package ru.fix.armeria.micrometer

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.metric.MetricCollectingClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.server.annotation.Get
import io.kotest.assertions.timing.eventually
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.logging.LoggingMeterRegistry
import io.micrometer.core.instrument.logging.LoggingRegistryConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
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
import ru.fix.armeria.micrometer.tags.MetricTags
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

@ExperimentalTime
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeterIdPrefixFunctionCustomizersTest {

    companion object : Logging {
        private val clientFactory = ClientFactory.ofDefault()
    }

    private lateinit var meterRegistry: MeterRegistry

    @BeforeEach
    fun setUp() {
        meterRegistry = CompositeMeterRegistry(
            Clock.SYSTEM,
            listOf(
                LoggingMeterRegistry(
                    object : LoggingRegistryConfig by LoggingRegistryConfig.DEFAULT {
                        override fun step(): Duration = Duration.ofSeconds(2)
                    },
                    Clock.SYSTEM
                ),
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
    fun `WHEN http request path customizer added AND max amount of path tag values restricted THEN metrics with exceeding tag values are removed`(): Unit =
        runBlocking {
            val testPath1 = "/testPath1"
            val testPath2 = "/testPath2"
            val testPath3 = "/testPath3"
            val testPaths = listOf(
                testPath1,
                testPath2,
                testPath3
            )
            ArmeriaMockServer {
                annotatedService(object : Any() {
                    @Get("/testPath1")
                    fun testPath1(): HttpResponse = delayedOkResponse()

                    @Get("/testPath2")
                    fun testPath2(): HttpResponse = delayedOkResponse()

                    @Get("/testPath3")
                    fun testPath3(): HttpResponse = delayedOkResponse()

                    private fun delayedOkResponse(): HttpResponse = HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.OK),
                        Duration.ofMillis(Random.nextLong(50, 200))
                    )
                })
            }.use { mockServer ->
                val metricsPrefix = "test.path.tag.limit"

                val expectedUniquePathTagValuesCount = testPaths.size - 1
                MeterIdPrefixFunctionCustomizers.HttpRequestPath.restrictMaxAmountOfPathTagValues(
                    meterRegistry,
                    metricsPrefix,
                    limitConfig = MaxMetricTagValuesLimitConfig(
                        maxCountOfUniqueTagValues = expectedUniquePathTagValuesCount.toByte()
                    )
                )

                val client = WebClient
                    .builder(mockServer.httpUri())
                    .factory(clientFactory)
                    .decorator(
                        MetricCollectingClient.newDecorator(
                            MeterIdPrefixFunction.ofDefault(metricsPrefix)
                                .andThen(MeterIdPrefixFunctionCustomizers.HttpRequestPath.customizer())
                        )
                    )
                    .build()

                val testRequestsCount = 1000
                coroutineScope {
                    for (i in (0 until testRequestsCount)) {
                        launch {
                            val testPath = testPaths.random()
                            client.get(testPath).aggregate().await()
                        }
                    }
                }

                eventually(5.toDuration(TimeUnit.SECONDS)) {
                    val totalDurationMetricName = "$metricsPrefix.total.duration"
                    val search = meterRegistry.find(totalDurationMetricName)

                    search.meters() should { meters ->
                        meters shouldHaveSize expectedUniquePathTagValuesCount

                        meters.forAll { meter ->
                            meter.id.name shouldBe totalDurationMetricName
                            meter.id.getTag(MetricTags.PATH).shouldNotBeNull()
                        }

                        val resultingPathTagValues = meters.map { it.id.getTag(MetricTags.PATH)!! }
                        resultingPathTagValues shouldHaveSize expectedUniquePathTagValuesCount
                    }
                }
            }
        }
}