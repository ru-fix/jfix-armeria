package ru.fix.armeria.dynamic.request.options

import com.linecorp.armeria.client.ResponseTimeoutException
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.testing.junit.server.mock.MockWebServerExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.throwable.shouldHaveCauseInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import ru.fix.dynamic.property.api.AtomicProperty
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration
import java.util.concurrent.CompletionException

internal class DynamicRequestOptionsClientTest {

    companion object {
        @JvmField
        @RegisterExtension
        val server = MockWebServerExtension()
    }

    @Test
    fun `WHEN read timeout changed THEN new value applied to subsequent requests`() {
        val serverResponseDelay = 1_000L
        val readTimeoutProperty = AtomicProperty(serverResponseDelay + 500)
        val client = WebClient.builder(server.httpUri())
            .decorator(
                DynamicRequestOptionsClient.newHttpDecorator(readTimeoutProperty, DynamicProperty.of(0))
            ).build()
        fun delayedResponse() =
            HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), Duration.ofMillis(serverResponseDelay))

        // no timeout happened
        server.enqueue(delayedResponse())
        val expectedToNotFailRequest = client.get("/")
        // change property and now timeout must take place on next request
        readTimeoutProperty.set(serverResponseDelay - 500)
        expectedToNotFailRequest.aggregate().join() should {
            it.status() shouldBe HttpStatus.OK
        }
        server.enqueue(delayedResponse())
        val thrownException = shouldThrow<CompletionException> {
            client.get("/").aggregate().join()
        }
        thrownException.shouldHaveCauseInstanceOf<ResponseTimeoutException>()
    }

    /*
     Unfortunately, have not found a way to emulate situation, when connection has been established,
     but the first message has not been sent out within the specified timeout.
     */
//    @Test
//    fun `WHEN write timeout changed THEN new value applied to subsequent requests`() { TODO
//    }
}