package ru.fix.armeria.facade

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.linecorp.armeria.client.ResponseTimeoutException
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import io.kotest.assertions.json.shouldMatchJson
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.timing.eventually
import io.kotest.inspectors.forAll
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.throwable.shouldHaveCauseOfType
import io.kotest.matchers.types.shouldBeTypeOf
import org.apache.logging.log4j.kotlin.Logging
import org.junit.jupiter.api.Test
import retrofit2.converter.jackson.JacksonConverterFactory
import retrofit2.create
import retrofit2.http.Body
import retrofit2.http.POST
import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.armeria.commons.testing.delayedOn
import ru.fix.armeria.commons.testing.j
import ru.fix.armeria.dynamic.request.endpoint.SocketAddress
import ru.fix.armeria.facade.ProfilerTestUtils.profiledCallReportWithName
import ru.fix.dynamic.property.api.AtomicProperty
import ru.fix.dynamic.property.api.DynamicProperty
import ru.fix.stdlib.socket.SocketChecker
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
internal class HttpClientsTest {

    @Test
    suspend fun `client with next features - profiled, dynamically configured, rate limited, retrying on 503 and connect error`() {
        val mockServer = ArmeriaMockServer("test-1-armeria-mock-server", defaultServicePath = PATH).start()
        val mockServerAddress = SocketAddress("localhost", mockServer.httpPort())
        val nonExistingServerPort = SocketChecker.getAvailableRandomPort()
        val nonExistingServerAddress = SocketAddress("localhost", nonExistingServerPort)
        val addressListProperty = AtomicProperty(listOf(mockServerAddress))
        val profiler = AggregatingProfiler()
        val reporter = profiler.createReporter()
        val wholeRequestTimeoutProperty = AtomicProperty(1.seconds)
        try {
            val clientName = "test-1-client"
            HttpClients.builder()
                .setClientName(clientName)
                //dynamic endpoints
                .setDynamicEndpoints(addressListProperty)
                .setIoThreadsCount(2)
                //retrying
                .withRetriesOn503AndUnprocessedError(4)
                //profiling
                .enableEachAttemptProfiling(profiler)
                .enableWholeRequestProfiling(profiler)
                //dynamic response timeouts
                .withCustomTimeouts()
                .setResponseTimeouts(
                    eachAttemptTimeout = 500.milliseconds.j,
                    wholeRequestTimeoutProp = wholeRequestTimeoutProperty.map { it.j }
                )
                //retrofit support
                .enableRetrofitSupport()
                .addConverterFactory(JacksonConverterFactory.create(jacksonObjectMapper))
                .setBlockingResponseReadingExecutor(
                    Executors.newSingleThreadExecutor(),
                    DynamicProperty.of(2.seconds.j)
                )
                .buildRetrofit().use { closeableRetrofit ->
                    val testEntityApi = closeableRetrofit.retrofit.create<TestEntityCountingApi>()

                    // Scenario 1. successful request to existing endpoint
                    mockServer.enqueue {
                        HttpResponse.of(TestEntity("return value").jsonStr)
                            .delayedOn(250.milliseconds)
                    }
                    val inputTestEntity1 = TestEntity("input value")

                    val result1 = testEntityApi.getTestEntity(inputTestEntity1)

                    result1.strField shouldBe "return value"
                    mockServer.pollRecordedRequest() should {
                        it.shouldNotBeNull()
                        it.request.contentUtf8() shouldMatchJson inputTestEntity1.jsonStr
                    }


                    // Scenario 2. timeouted response
                    wholeRequestTimeoutProperty.set(250.milliseconds)
                    val inputTestEntity2 = TestEntity("return value 2")
                    mockServer.enqueue {
                        HttpResponse.of(inputTestEntity2.jsonStr)
                            .delayedOn(400.milliseconds)
                    }

                    val thrownExc = shouldThrowAny {
                        testEntityApi.getTestEntity(inputTestEntity2)
                    }
                    thrownExc should {
                        it.shouldBeTypeOf<IOException>()
                        it.shouldHaveCauseOfType<IOException>()
                        val cause = it.cause
                        cause.shouldNotBeNull()
                        cause.shouldHaveCauseOfType<ResponseTimeoutException>()
                    }
                    mockServer.pollRecordedRequest() should {
                        it.shouldNotBeNull()
                        it.request.contentUtf8() shouldMatchJson inputTestEntity2.jsonStr
                    }


                    // Scenario 3. load-balancing and retrying on 503/connect_error
                    wholeRequestTimeoutProperty.set(1.seconds)
                    addressListProperty.set(listOf(mockServerAddress, nonExistingServerAddress))
                    mockServer.enqueue {
                        HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE).delayedOn(100.milliseconds)
                    }
                    mockServer.enqueue {
                        HttpResponse.of(TestEntity("return value 3").jsonStr)
                    }
                    val inputTestEntity3 = TestEntity("input value 3")

                    val result3 = testEntityApi.getTestEntity(inputTestEntity3)

                    result3.strField shouldBe "return value 3"
                    listOf(
                        mockServer.pollRecordedRequest(),
                        mockServer.pollRecordedRequest()
                    ) should { recordedRequests ->
                        recordedRequests.forAll {
                            it.shouldNotBeNull()
                            it.request.contentUtf8() shouldMatchJson inputTestEntity3.jsonStr
                        }
                    }
                    // metrics are written asynchronously
                    eventually(500.milliseconds) {
                        val attemptErrorMetricName =
                            "$clientName.${Metrics.EACH_RETRY_ATTEMPT_PREFIX}.http.error"
                        val report = reporter.buildReportAndReset { metric, _ ->
                            metric.name == attemptErrorMetricName && metric.hasTag("error_type", "connect_refused")
                        }
                        logger.trace { "Report: $report" }
                        report.profiledCallReportWithName(attemptErrorMetricName) should {
                            it.shouldNotBeNull()
                            it.stopSum shouldBe 1
                            it.identity.tags shouldContainAll mapOf(
                                "remote_port" to nonExistingServerPort.toString(),
                                "error_type" to "connect_refused"
                            )
                        }
                    }
                }
        } finally {
            mockServer.stop()
        }
    }


    interface TestEntityCountingApi {

        @POST(PATH)
        suspend fun getTestEntity(@Body testEntity: TestEntity): TestEntity
    }

    data class TestEntity(
        val strField: String
    ) {
        @JsonIgnore
        val jsonStr = """{"strField":"$strField"}"""
    }

    companion object: Logging {
        const val PATH = "/getTestEntity"

        val jacksonObjectMapper = ObjectMapper().findAndRegisterModules()

        fun createTestEntityCountedMockServer(mockServerNamePrefix: String): ArmeriaMockServer =
            ArmeriaMockServer(mockServerName = "$mockServerNamePrefix-armeria-mock-server", defaultServicePath = PATH) {
                decorator { delegate, ctx, req ->
                    ctx.mutateAdditionalResponseHeaders {
                        it.contentType(MediaType.JSON)
                    }
                    delegate.serve(ctx, req)
                }
            }
    }
}