package ru.fix.armeria.facade.webclient.impl

import ru.fix.armeria.facade.Either
import com.linecorp.armeria.client.*
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.common.Flags
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.util.EventLoopGroups
import org.apache.logging.log4j.kotlin.Logging
import ru.fix.aggregating.profiler.PrefixedProfiler
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.aggregating.profiler.ProfiledConnectionPoolListener
import ru.fix.armeria.commons.AutoCloseableHttpClient
import ru.fix.armeria.dynamic.request.endpoint.DynamicAddressEndpoints
import ru.fix.armeria.dynamic.request.endpoint.SocketAddress
import ru.fix.armeria.facade.retrofit.RetrofitHttpClientBuilder
import ru.fix.armeria.facade.retrofit.impl.RetrofitHttpClientBuilderImpl
import ru.fix.armeria.facade.webclient.BaseHttpClientBuilder
import ru.fix.armeria.facade.webclient.CloseableWebClient
import ru.fix.armeria.limiter.RateLimitingHttpClient
import ru.fix.dynamic.property.api.DynamicProperty
import ru.fix.stdlib.ratelimiter.RateLimitedDispatcher
import java.net.URI
import java.time.Duration
import java.util.function.Function
import kotlin.system.measureTimeMillis

internal data class BaseHttpClientBuilderState(
    val clientNameCreator: () -> String = {
        throw IllegalStateException("clientName must be set")
    },
    val sessionProtocol: SessionProtocol = SessionProtocol.HTTP,
    val ignoreEndpoints: Boolean = false,
    val endpointGroupCreator: () -> Either<Lazy<EndpointGroup>, URI> = {
        throw IllegalStateException("endpoint/endpoint_group or URI must be set")
    },
    val ioThreadsCount: Int? = null,
    val clientFactoryBuilder: () -> ClientFactoryBuilder = { ClientFactory.builder() },
    val clientFactoryBuilderCustomizer: ClientFactoryBuilder.() -> ClientFactoryBuilder = { this },
    val clientOptionsBuilder: () -> ClientOptionsBuilder = { ClientOptions.builder() },
    val clientOptionsBuilderCustomizer: ClientOptionsBuilder.() -> ClientOptionsBuilder = { this },
    val rateLimitingDecorator: Function<HttpClient, AutoCloseableHttpClient<*>>? = null
)

internal abstract class BaseHttpClientBuilderImpl<out HttpClientBuilderT : BaseHttpClientBuilder<HttpClientBuilderT>>(
    protected val baseBuilderState: BaseHttpClientBuilderState
) : BaseHttpClientBuilder<HttpClientBuilderT> {

    companion object : Logging

    private lateinit var clientName: String

    internal val lazyClientName: () -> String
        get() = baseBuilderState.clientNameCreator

    override fun setClientName(clientName: String) = copyOfThisBuilder(
        baseBuilderState.copy(clientNameCreator = { clientName })
    )

    override fun setIgnoreEndpoint(ignore: Boolean) = copyOfThisBuilder(
            baseBuilderState.copy(ignoreEndpoints = ignore)
    )

    override fun setEndpoint(uri: URI) = copyOfThisBuilder(
        baseBuilderState.copy(endpointGroupCreator = {
            Either.Right(uri)
        })
    )

    override fun setEndpoint(host: String, port: Int) = copyOfThisBuilder(
        baseBuilderState.copy(endpointGroupCreator = {
            Either.Left(lazy {
                Endpoint.of(host, port)
            })
        })
    )

    override fun setDynamicEndpoint(addressProperty: DynamicProperty<SocketAddress>) = copyOfThisBuilder(
        baseBuilderState.copy(endpointGroupCreator = {
            Either.Left(lazy {
                DynamicAddressEndpoints.dynamicAddressEndpoint(
                    addressProperty
                )
            })
        })
    )

    override fun setDynamicEndpoints(addressListProperty: DynamicProperty<List<SocketAddress>>) =
        copyOfThisBuilder(
            baseBuilderState.copy(endpointGroupCreator = {
                Either.Left(lazy {
                    DynamicAddressEndpoints.dynamicAddressListEndpointGroup(addressListProperty)
                })
            })
        )

    override fun setDynamicEndpoints(
        addressListProperty: DynamicProperty<List<SocketAddress>>,
        endpointSelectionStrategy: EndpointSelectionStrategy
    ) = copyOfThisBuilder(
        baseBuilderState.copy(endpointGroupCreator = {
            Either.Left(lazy {
                DynamicAddressEndpoints.dynamicAddressListEndpointGroup(addressListProperty, endpointSelectionStrategy)
            })
        })
    )

    override fun setEndpointGroup(endpointGroup: EndpointGroup): HttpClientBuilderT = copyOfThisBuilder(
        baseBuilderState.copy(endpointGroupCreator = {
            Either.Left(lazyOf(endpointGroup))
        })
    )

    override fun setIoThreadsCount(count: Int) = copyOfThisBuilder(
        baseBuilderState.copy(ioThreadsCount = count)
    )

    override fun setConnectTimeout(duration: Duration) = copyOfThisBuilder(
        baseBuilderState.copy(clientFactoryBuilder = {
            baseBuilderState.clientFactoryBuilder().connectTimeout(duration)
        })
    )

    override fun setConnectionTtl(duration: Duration) = copyOfThisBuilder(
        baseBuilderState.copy(clientFactoryBuilder = {
            baseBuilderState.clientFactoryBuilder().idleTimeout(duration)
        })
    )

    override fun setUseHttp2Preface(useHttp2Preface: Boolean) = copyOfThisBuilder(
        baseBuilderState.copy(clientFactoryBuilder = {
            baseBuilderState.clientFactoryBuilder().useHttp2Preface(useHttp2Preface)
        })
    )

    override fun enableConnectionsProfiling(profiler: Profiler) = copyOfThisBuilder(
        baseBuilderState.copy(clientFactoryBuilder = {
            baseBuilderState.clientFactoryBuilder().connectionPoolListener(
                ProfiledConnectionPoolListener(
                    PrefixedProfiler(profiler, baseBuilderState.clientNameCreator()),
                    ConnectionPoolListener.noop()
                )
            )
        })
    )

    override fun enableRateLimit(rateLimitedDispatcher: RateLimitedDispatcher): HttpClientBuilderT = copyOfThisBuilder(
        baseBuilderState.copy(rateLimitingDecorator = RateLimitingHttpClient.newDecorator(rateLimitedDispatcher))
    )

    override fun buildArmeriaWebClient(): CloseableWebClient {
        require(!this::clientName.isInitialized) {
            "Http client can build client only once"
        }
        clientName = baseBuilderState.clientNameCreator()

        val endpointGroup: EndpointGroup?
        val webClientBuilder = if (baseBuilderState.ignoreEndpoints) {
            endpointGroup = null
            WebClient.builder()
        } else {
            val endpoints = baseBuilderState.endpointGroupCreator()
            endpointGroup = endpoints.leftOrNull?.value
            when (endpoints) {
                is Either.Left -> WebClient.builder(baseBuilderState.sessionProtocol, endpoints.value.value)
                is Either.Right -> WebClient.builder(endpoints.value)
            }
        }
        // build ClientFactory
        var clientFactoryBuilder: ClientFactoryBuilder = baseBuilderState.clientFactoryBuilder()
        clientFactoryBuilder = clientFactoryBuilder.workerGroup(
            EventLoopGroups.newEventLoopGroup(
                baseBuilderState.ioThreadsCount ?: Flags.numCommonWorkers(),
                "$clientName-eventloop"
            ),
            true
        )
        val clientFactoryBuilderCustomizer = baseBuilderState.clientFactoryBuilderCustomizer
        val clientFactory = clientFactoryBuilder
            .clientFactoryBuilderCustomizer()
            .build()


        // build ClientOptions
        var clientOptionsBuilder: ClientOptionsBuilder = baseBuilderState.clientOptionsBuilder()
        val (enrichedClientOptionsBuilder, closeableDecorators) = clientOptionsBuilder.enrichClientOptionsBuilder()
        clientOptionsBuilder = enrichedClientOptionsBuilder

        val clientOptionsBuilderCustomizer = baseBuilderState.clientOptionsBuilderCustomizer
        val clientOptions = clientOptionsBuilder
            .clientOptionsBuilderCustomizer()
            .build()


        return CloseableWebClient(
            webClientBuilder
                .factory(clientFactory)
                .options(clientOptions)
                .build(),
            AutoCloseable {
                logger.info { """Closing Armeria http client "$clientName"...""" }
                val closingTimeMillis = measureTimeMillis {
                    for (closeableDecorator in closeableDecorators) {
                        logger.info { "$clientName: closing decorator $closeableDecorator ..." }
                        closeableDecorator.close()
                    }
                    logger.info { "$clientName: closing http client factory under the hood..." }
                    clientFactory.close()
                    if (endpointGroup != null) {
                        logger.info { "$clientName: closing endpoint (group)..." }
                        endpointGroup.close()
                    }
                }
                logger.info { """Armeria http client "$clientName" closed in $closingTimeMillis ms.""" }
            }
        )
    }

    protected open fun ClientOptionsBuilder.enrichClientOptionsBuilder()
            : Pair<ClientOptionsBuilder, List<AutoCloseableHttpClient<*>>> {
        val closeableDecorators: MutableList<AutoCloseableHttpClient<*>> = mutableListOf()

        val clientOptionsBuilder = this
            .withRateLimitingDecorator(closeableDecorators)

        return clientOptionsBuilder to closeableDecorators
    }

    protected fun ClientOptionsBuilder.withRateLimitingDecorator(
        closeableDecorators: MutableList<AutoCloseableHttpClient<*>>
    ): ClientOptionsBuilder =
        this.let { optionsBuilder ->
            baseBuilderState.rateLimitingDecorator?.let {
                optionsBuilder.decorator(it.andThen { closeableDecorator ->
                    closeableDecorators.add(closeableDecorator)
                    closeableDecorator
                })
            } ?: optionsBuilder
        }

    override fun enableRetrofitSupport(): RetrofitHttpClientBuilder = RetrofitHttpClientBuilderImpl(this)

    override fun setSessionProtocol(sessionProtocol: SessionProtocol): HttpClientBuilderT = copyOfThisBuilder(
        baseBuilderState.copy(sessionProtocol = sessionProtocol)
    )

    override fun customizeArmeriaClientFactoryBuilder(
        customizer: ClientFactoryBuilder.() -> ClientFactoryBuilder
    ): HttpClientBuilderT = copyOfThisBuilder(
        baseBuilderState.copy(clientFactoryBuilderCustomizer = customizer)
    )

    override fun customizeArmeriaClientOptionsBuilder(
        customizer: ClientOptionsBuilder.() -> ClientOptionsBuilder
    ): HttpClientBuilderT = copyOfThisBuilder(
        baseBuilderState.copy(clientOptionsBuilderCustomizer = customizer)
    )

    protected abstract fun copyOfThisBuilder(
        baseBuilderStateBase: BaseHttpClientBuilderState = this.baseBuilderState
    ): HttpClientBuilderT

    override fun toString(): String {
        return "HttpClientBuilder(clientName=${if (this::clientName.isInitialized) clientName else "not_used_client"})"
    }

}