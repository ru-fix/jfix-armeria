package ru.fix.armeria.commons.testing

import com.linecorp.armeria.client.Endpoint
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.SerializationFormat
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.util.EventLoopGroups
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

class ArmeriaMockServer(
    mockServerName: String = "armeria-mock-server",
    defaultServicePath: String = "/",
    serverBuilderCustomizer: ServerBuilder.() -> ServerBuilder = { this }
) {

    private val recordedRequests = ConcurrentLinkedQueue<RecordedRequest>()
    private val mockResponses = ConcurrentLinkedQueue<() -> HttpResponse>()

    private val server = Server.builder()
        .http(0)
        .https(0)
        .tlsSelfSigned()
        .workerGroup(EventLoopGroups.newEventLoopGroup(1, "$mockServerName-eventloop-worker"), true)
        .service(defaultServicePath) { ctx, req ->
            HttpResponse.from(req.aggregate().thenApply {
                recordedRequests.offer(RecordedRequest(it, ctx))

                mockResponses.poll()?.invoke()
                    ?: throw IllegalStateException(
                        "No mock response configured. Did you call enqueueResponse? Request: $it"
                    )
            })
        }
        .serverBuilderCustomizer()
        .build()

    private val serverStarted: Boolean
        get() = server.activePorts().isNotEmpty()

    private fun serverHasSessionProtocol(protocol: SessionProtocol): Boolean =
        server.activePorts().filterValues { it.hasProtocol(protocol) }.isNotEmpty()

    suspend fun start(): ArmeriaMockServer = apply {
        server.start().await()
    }

    fun launchStart(): Job = GlobalScope.launch { start() }

    suspend fun stop(): ArmeriaMockServer = apply {
        server.stop().await()
    }

    suspend inline fun use(action: (mockServer: ArmeriaMockServer) -> Unit) {
        start()
        try {
            action(this)
        } finally {
            stop()
        }
    }

    fun launchStop(): Job = GlobalScope.launch { stop() }

    fun enqueue(httpResponseCreator: () -> HttpResponse) {
        mockResponses.add(httpResponseCreator)
    }

    fun pollRecordedRequest(): RecordedRequest? = recordedRequests.poll()

    fun port(protocol: SessionProtocol) = server.activeLocalPort(protocol)

    fun httpPort(): Int = port(SessionProtocol.HTTP)

    fun httpsPort(): Int = port(SessionProtocol.HTTPS)

    fun endpoint(sessionProtocol: SessionProtocol, localHost: LocalHost = LocalHost.HOSTNAME): Endpoint =
        Endpoint.of(localHost.value, port(sessionProtocol))

    fun httpEndpoint() = endpoint(SessionProtocol.HTTP)

    fun httpsEndpoint() = endpoint(SessionProtocol.HTTPS)

    fun httpUri(): URI = uri(SessionProtocol.HTTP)

    fun httpsUri(): URI = uri(SessionProtocol.HTTPS)

    fun uri(
        protocol: SessionProtocol,
        localHost: LocalHost = LocalHost.HOSTNAME,
        format: SerializationFormat = SerializationFormat.NONE
    ): URI {
        require(serverStarted)

        val port: Int = when {
            !protocol.isTls && serverHasSessionProtocol(SessionProtocol.HTTP) -> {
                server.activeLocalPort(SessionProtocol.HTTP)
            }

            protocol.isTls && serverHasSessionProtocol(SessionProtocol.HTTPS) -> {
                server.activeLocalPort(SessionProtocol.HTTPS)
            }

            else -> {
                throw IllegalStateException("can't find the specified port")
            }
        }

        val uriStr = "${protocol.uriText()}://${localHost.value}:$port"
        return if (format === SerializationFormat.NONE) {
            URI.create(uriStr)
        } else {
            URI.create(format.uriText() + '+' + uriStr)
        }
    }

    data class RecordedRequest(
        val request: AggregatedHttpRequest,
        val requestContext: ServiceRequestContext
    )

}

@ExperimentalTime
fun HttpResponse.delayedOn(delay: Duration): HttpResponse = HttpResponse.delayed(this, delay.j)
