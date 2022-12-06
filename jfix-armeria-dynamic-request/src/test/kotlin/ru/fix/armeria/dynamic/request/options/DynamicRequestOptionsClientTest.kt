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
import ru.fix.armeria.commons.testing.j
import ru.fix.dynamic.property.api.AtomicProperty
import java.util.concurrent.TimeUnit
import kotlin.time.*

@ExperimentalTime
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
        val readTimeoutProperty = AtomicProperty(5.toDuration(TimeUnit.SECONDS))
        val client = WebClient.builder(mockServer.httpUri())
            .decorator(
                DynamicRequestOptionsClient.newHttpDecoratorWithReadTimeout(readTimeoutProperty.map { it.j })
            ).build()
        val delayedResponseCreator: () -> HttpResponse = {
            HttpResponse.delayed(HttpResponse.of(HttpStatus.OK), 1.toDuration(TimeUnit.SECONDS).j)
        }

        // no timeout happened
        mockServer.enqueue(delayedResponseCreator)
        val expectedToNotFailRequest = client.get("/")
        expectedToNotFailRequest.aggregate().await() should {
            it.status() shouldBe HttpStatus.OK
        }
        // change property and now timeout must take place on next request
        readTimeoutProperty.set(500.toDuration(TimeUnit.MILLISECONDS))
        mockServer.enqueue(delayedResponseCreator)
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