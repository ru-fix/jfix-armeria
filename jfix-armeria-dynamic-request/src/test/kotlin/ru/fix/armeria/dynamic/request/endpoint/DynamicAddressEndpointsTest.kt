package ru.fix.armeria.dynamic.request.endpoint

import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.endpoint.EndpointSelectionTimeoutException
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.SessionProtocol
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.throwable.shouldHaveCauseInstanceOf
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.joinAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.dynamic.property.api.AtomicProperty
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DynamicAddressEndpointsTest {

    @Test
    suspend fun `dynamicAddressEndpoint WHEN property changed THEN requests are targeted to other address`() {
        val (mockServer1, mockServer1RequestsCounter) = createMockServerWithRequestsCount()
        val (mockServer2, mockServer2RequestsCounter) = createMockServerWithRequestsCount()
        try {
            listOf(
                mockServer1.launchStart(),
                mockServer2.launchStart()
            ).joinAll()
            val mockServer1Address = mockServer1.httpUri().asSocketAddress()
            val mockServer2Address = mockServer2.httpUri().asSocketAddress()
            val dynamicAddressProp = AtomicProperty<SocketAddress>(mockServer1Address)
            val dynamicAddressEndpoint = DynamicAddressEndpoints.dynamicAddressEndpoint(dynamicAddressProp)
            val client = WebClient.builder(SessionProtocol.HTTP, dynamicAddressEndpoint).build()


            client.makeCountedRequest().await()
            mockServer1RequestsCounter.get() shouldBe 1
            mockServer2RequestsCounter.get() shouldBe 0

            dynamicAddressProp.set(mockServer2Address)
            client.makeCountedRequest().await()
            mockServer1RequestsCounter.get() shouldBe 1
            mockServer2RequestsCounter.get() shouldBe 1

            dynamicAddressProp.set(mockServer1Address)
            client.makeCountedRequest().await()
            mockServer1RequestsCounter.get() shouldBe 2
            mockServer2RequestsCounter.get() shouldBe 1
        } finally {
            listOf(
                mockServer1.launchStop(),
                mockServer2.launchStop()
            ).joinAll()
        }

    }

    @Test
    suspend fun `dynamicAddressListEndpointGroup WHEN property changed THEN requests are targeted to other addresses`() {
        val (mockServer1, mockServer1RequestsCounter) = createMockServerWithRequestsCount()
        val (mockServer2, mockServer2RequestsCounter) = createMockServerWithRequestsCount()
        val (mockServer3, mockServer3RequestsCounter) = createMockServerWithRequestsCount()
        fun assertRequestCounters(
            expectedMockServer1RequestsCounter: Int,
            expectedMockServer2RequestsCounter: Int,
            expectedMockServer3RequestsCounter: Int
        ) {
            mockServer1RequestsCounter.get() shouldBe expectedMockServer1RequestsCounter
            mockServer2RequestsCounter.get() shouldBe expectedMockServer2RequestsCounter
            mockServer3RequestsCounter.get() shouldBe expectedMockServer3RequestsCounter
        }
        try {
            listOf(
                mockServer1.launchStart(),
                mockServer2.launchStart(),
                mockServer3.launchStart()
            ).joinAll()
            val mockServer1Address = mockServer1.httpUri().asSocketAddress()
            val mockServer2Address = mockServer2.httpUri().asSocketAddress()
            val mockServer3Address = mockServer3.httpUri().asSocketAddress()
            val dynamicAddressListProp = AtomicProperty<List<SocketAddress>>(
                listOf(
                    mockServer1Address
                )
            )
            val dynamicAddressListEndpointGroup = DynamicAddressEndpoints.dynamicAddressListEndpointGroup(
                dynamicAddressListProp
            )
            val client = WebClient.builder(SessionProtocol.HTTP, dynamicAddressListEndpointGroup).build()

            List(2) {
                client.makeCountedRequest()
            }.awaitAll()
            assertRequestCounters(2, 0, 0)

            dynamicAddressListProp.set(listOf(mockServer2Address, mockServer3Address))
            List(2) {
                client.makeCountedRequest()
            }.awaitAll()
            assertRequestCounters(2, 1, 1)

            dynamicAddressListProp.set(listOf(mockServer1Address, mockServer3Address))
            List(2) {
                client.makeCountedRequest()
            }.awaitAll()
            assertRequestCounters(3, 1, 2)

            dynamicAddressListProp.set(emptyList())
            val thrownException = shouldThrowExactly<UnprocessedRequestException> {
                client.makeCountedRequest().await()
            }
            thrownException.shouldHaveCauseInstanceOf<EndpointSelectionTimeoutException>()
        } finally {
            listOf(
                mockServer1.launchStop(),
                mockServer2.launchStop(),
                mockServer3.launchStop()
            ).joinAll()
        }
    }

    companion object {

        const val COUNTED_PATH = "/counted"

        fun URI.asSocketAddress(): SocketAddress = SocketAddress(this.host, this.port)

        fun WebClient.makeCountedRequest(): Deferred<*> = get(COUNTED_PATH).aggregate().asDeferred()

        fun createMockServerWithRequestsCount(): Pair<ArmeriaMockServer, AtomicInteger> {
            val requestsCounter = AtomicInteger(0)
            val mockServer = ArmeriaMockServer {
                service(COUNTED_PATH) { _, _ ->
                    requestsCounter.incrementAndGet()
                    HttpResponse.of(HttpStatus.OK)
                }
            }
            return mockServer to requestsCounter
        }
    }
}