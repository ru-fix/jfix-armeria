package ru.fix.armeria.dynamic.request.options

import com.linecorp.armeria.client.ResponseTimeoutException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.fix.armeria.commons.testing.ArmeriaMockServer
import ru.fix.dynamic.property.api.AtomicProperty
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration

internal class DynamicRequestOptionsClientTest {

    val mockServer = ArmeriaMockServer()

    @BeforeEach
    suspend fun setUp() {
        mockServer.start()
    }

    @AfterEach
    suspend fun tearDown() {
        mockServer.stop()
    }

    @Test
    suspend fun `WHEN read timeout changed THEN new value applied to subsequent requests`() {
        val serverResponseDelay = 1_000L
        val readTimeoutProperty = AtomicProperty(serverResponseDelay + 500)
        val client = WebClient.builder(mockServer.httpUri())
            .decorator(
                DynamicRequestOptionsClient.newHttpDecorator(readTimeoutProperty, DynamicProperty.of(0))
            ).build()
        fun delayedResponse() =
            HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(serverResponseDelay))

        // no timeout happened
        mockServer.enqueue(delayedResponse())
        val expectedToNotFailRequest = client.get("/")
        // change property and now timeout must take place on next request
        readTimeoutProperty.set(serverResponseDelay - 500)
        expectedToNotFailRequest.aggregate().await() should {
            it.status() shouldBe HttpStatus.OK
        }
        mockServer.enqueue(delayedResponse())
        shouldThrow<ResponseTimeoutException> {
            client.get("/").aggregate().await()
        }
    }

    /*
     Unfortunately, have not found a way to emulate situation, when connection has been established,
     but the first message has not been sent out within the specified timeout.
     */
//    @Test
//    fun `WHEN write timeout changed THEN new value applied to subsequent requests`() { TODO
//    }
}