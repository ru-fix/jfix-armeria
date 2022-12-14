package ru.fix.armeria.micrometer

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.metric.MetricCollectingClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.Get
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.timing.continually
import io.kotest.assertions.timing.eventually
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import io.micrometer.core.instrument.logging.LoggingMeterRegistry
import io.micrometer.core.instrument.logging.LoggingRegistryConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.micrometer.tags.MetricTags
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
            val testPaths = listOf("/testPath1", "/testPath2", "/testPath3")
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
                        launch(Dispatchers.Default) {
                            val testPath = testPaths.random()
                            client.get(testPath).aggregate().await()
                        }
                    }
                }

                assertTagHasExpectedUniqueValuesCount(
                    metricsPrefix,
                    tagKey = MetricTags.PATH,
                    expectedUniquePathTagValuesCount
                )
            }
        }

    private fun remoteAddressInfoLimitTestCases(): List<Arguments> = listOf(
        Arguments.of(
            RemoteEndpointTagTestCaseConfig(maxCountOfUniqueTagValues = 0, onMaxReachedMustBeCalled = true),
            RemoteEndpointTagTestCaseConfig(maxCountOfUniqueTagValues = 10, onMaxReachedMustBeCalled = false),
        ),
        Arguments.of(
            RemoteEndpointTagTestCaseConfig(maxCountOfUniqueTagValues = 10, onMaxReachedMustBeCalled = false),
            RemoteEndpointTagTestCaseConfig(maxCountOfUniqueTagValues = 0, onMaxReachedMustBeCalled = true)
        )
    )

    @ParameterizedTest
    @MethodSource("remoteAddressInfoLimitTestCases")
    fun `WHEN remote address info customizer added AND max amount of remote address or host tags values is restricted THEN metrics with exceeding tag values are removed`(
        remoteAddressTestCaseConfig: RemoteEndpointTagTestCaseConfig,
        remoteHostTestCaseConfig: RemoteEndpointTagTestCaseConfig,
    ) =
        runBlocking {
            val testPath = "/testPath"
            ArmeriaMockServer {
                annotatedService(object : Any() {
                    @Get("/testPath")
                    fun testPath(): HttpResponse = delayedOkResponse()

                    private fun delayedOkResponse(): HttpResponse = HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.OK),
                        Duration.ofMillis(Random.nextLong(50, 200))
                    )
                })
            }.use { mockServer ->
                val metricsPrefix = "test.remote_address_host.tag.limit"
                val remoteAddressOnMaxReachedMeterFilterCalled = AtomicBoolean(false)
                val remoteHostOnMaxReachedMeterFilterCalled = AtomicBoolean(false)

                /*
                TODO
                 Due to challenge in creation of separate hostnames/ip addresses for testing,
                 in order have a minimal restriction auto check
                 it has been decided to check that with maxCount of 0 no metrics will be passed through.
                 */
                MeterIdPrefixFunctionCustomizers.RemoteAddressInfo.restrictMaxAmountOfAddressInfoTagsValues(
                    meterRegistry,
                    metricsPrefix,
                    remoteAddressLimitConfig = createTestLimitConfig(
                        remoteAddressTestCaseConfig,
                        remoteAddressOnMaxReachedMeterFilterCalled
                    ),
                    remoteHostLimitConfig = createTestLimitConfig(
                        remoteHostTestCaseConfig,
                        remoteHostOnMaxReachedMeterFilterCalled
                    ),
                )

                val client = WebClient
                    .builder(mockServer.httpUri())
                    .factory(clientFactory)
                    .decorator(
                        MetricCollectingClient.newDecorator(
                            MeterIdPrefixFunction.ofDefault(metricsPrefix)
                                .andThen(
                                    MeterIdPrefixFunctionCustomizers.RemoteAddressInfo.remoteEndpointInfoCustomizer()
                                )
                        )
                    )
                    .build()

                val testRequestsCount = 1000
                coroutineScope {
                    for (i in (0 until testRequestsCount)) {
                        launch(Dispatchers.Default) {
                            client.get(testPath).aggregate().await()
                        }
                    }
                }

                continually(2.toDuration(TimeUnit.SECONDS)) {
                    val totalDurationMetricName = "$metricsPrefix.total.duration"
                    val search = meterRegistry.find(totalDurationMetricName)

                    search.meters().shouldBeEmpty()
                }
                assertSoftly {
                    remoteAddressOnMaxReachedMeterFilterCalled.get() shouldBe
                            remoteAddressTestCaseConfig.onMaxReachedMustBeCalled
                    remoteHostOnMaxReachedMeterFilterCalled.get() shouldBe
                            remoteHostTestCaseConfig.onMaxReachedMustBeCalled
                }
            }
        }

    private fun createTestLimitConfig(
        remoteAddressTestCaseConfig: RemoteEndpointTagTestCaseConfig,
        remoteAddressOnMaxReachedMeterFilterCalled: AtomicBoolean
    ) = MaxMetricTagValuesLimitConfig(
        remoteAddressTestCaseConfig.maxCountOfUniqueTagValues
    ).let {
        it.copy(
            getOnMaxReachedMeterFilter = { metricNamePrefix, metricTagKey ->
                AcceptCallListeningMeterFilter(
                    it.getOnMaxReachedMeterFilter.invoke(metricNamePrefix, metricTagKey)
                ) {
                    remoteAddressOnMaxReachedMeterFilterCalled.set(true)
                }
            }
        )
    }

    @Test
    fun `WHEN remote address info customizer added AND max amount of remote port tag values is restricted THEN metrics with exceeding tag values are removed`() =
        runBlocking {
            val testPath = "/testPath"
            val serverBuilderCustomizer: ServerBuilder.() -> ServerBuilder = {
                annotatedService(object : Any() {
                    @Get("/testPath")
                    fun testPath(): HttpResponse = delayedOkResponse()

                    private fun delayedOkResponse(): HttpResponse = HttpResponse.delayed(
                        HttpResponse.of(HttpStatus.OK),
                        Duration.ofMillis(Random.nextLong(50, 200))
                    )
                })
            }
            ArmeriaMockServer(serverBuilderCustomizer = serverBuilderCustomizer).use { mockServer ->
                ArmeriaMockServer(serverBuilderCustomizer = serverBuilderCustomizer).use { mockServer2 ->
                    val metricsPrefix = "test.port.tag.limit"

                    val expectedUniquePortTagValuesCount = 1
                    MeterIdPrefixFunctionCustomizers.RemoteAddressInfo.restrictMaxAmountOfAddressInfoTagsValues(
                        meterRegistry,
                        metricsPrefix,
                        remotePortLimitConfig = MaxMetricTagValuesLimitConfig(
                            maxCountOfUniqueTagValues = expectedUniquePortTagValuesCount.toByte()
                        )
                    )

                    val client = WebClient
                        .builder(
                            SessionProtocol.HTTP,
                            EndpointGroup.of(
                                mockServer.httpEndpoint(),
                                mockServer2.httpEndpoint()
                            )
                        ).factory(clientFactory)
                        .decorator(
                            MetricCollectingClient.newDecorator(
                                MeterIdPrefixFunction.ofDefault(metricsPrefix)
                                    .andThen(
                                        MeterIdPrefixFunctionCustomizers.RemoteAddressInfo.remoteEndpointInfoCustomizer()
                                    )
                            )
                        )
                        .build()

                    val testRequestsCount = 1000
                    coroutineScope {
                        for (i in (0 until testRequestsCount)) {
                            launch(Dispatchers.Default) {
                                client.get(testPath).aggregate().await()
                            }
                        }
                    }

                    assertTagHasExpectedUniqueValuesCount(
                        metricsPrefix,
                        MetricTags.REMOTE_PORT,
                        expectedUniquePortTagValuesCount
                    )
                }
            }
        }

    private suspend fun assertTagHasExpectedUniqueValuesCount(
        metricsPrefix: String,
        tagKey: String,
        expectedUniqueTagValuesCount: Int
    ) {
        eventually(5.toDuration(TimeUnit.SECONDS)) {
            val totalDurationMetricName = "$metricsPrefix.total.duration"
            val search = meterRegistry.find(totalDurationMetricName)

            search.meters() should { meters ->
                meters shouldHaveSize expectedUniqueTagValuesCount

                meters.forAll { meter ->
                    meter.id.name shouldBe totalDurationMetricName
                    meter.id.getTag(tagKey).shouldNotBeNull()
                }

                val resultingTagValues = meters.map { it.id.getTag(tagKey)!! }
                resultingTagValues shouldHaveSize expectedUniqueTagValuesCount
            }
        }
    }

    data class RemoteEndpointTagTestCaseConfig(
        val maxCountOfUniqueTagValues: Byte,
        val onMaxReachedMustBeCalled: Boolean
    )

    class AcceptCallListeningMeterFilter(
        private val delegate: MeterFilter,
        private val acceptCallListener: (id: Meter.Id) -> Unit
    ) : MeterFilter by delegate {

        override fun accept(id: Meter.Id): MeterFilterReply {
            acceptCallListener.invoke(id)
            return delegate.accept(id)
        }
    }
}