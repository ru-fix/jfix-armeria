package ru.fix.armeria.facade.webclient.impl

import com.linecorp.armeria.client.*
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.util.EventLoopGroups
import org.apache.logging.log4j.kotlin.Logging
import ru.fix.aggregating.profiler.PrefixedProfiler
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.aggregating.profiler.ProfiledConnectionPoolListener
import ru.fix.armeria.commons.AutoCloseableHttpClient
import ru.fix.armeria.dynamic.request.endpoint.DynamicAddressEndpoints
import ru.fix.armeria.dynamic.request.endpoint.SocketAddress
import ru.fix.armeria.facade.Either
import ru.fix.armeria.facade.retrofit.RetrofitHttpClientBuilder
import ru.fix.armeria.facade.retrofit.impl.RetrofitHttpClientBuilderImpl
import ru.fix.armeria.facade.webclient.BaseHttpClientBuilder
import ru.fix.armeria.facade.webclient.ClientFactoryBuilderCustomizer
import ru.fix.armeria.facade.webclient.ClientOptionsBuilderCustomizer
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
    val endpointGroupCreator: () -> Either<Lazy<EndpointGroup>, URI>? = {
        null
    },
    val ioThreadsCount: Int? = null,
    val clientFactory: ClientFactory? = null,
    val clientFactoryBuilder: () -> ClientFactoryBuilder = { ClientFactory.builder() },
    val clientFactoryBuilderCustomizers: List<ClientFactoryBuilderCustomizer> = emptyList(),
    val clientOptionsBuilder: () -> ClientOptionsBuilder = { ClientOptions.builder() },
    val clientOptionsBuilderCustomizers: List<ClientOptionsBuilderCustomizer> = emptyList(),
    val rateLimitingDecorator: Function<HttpClient, AutoCloseableHttpClient<*>>? = null
)

internal abstract class BaseHttpClientBuilderImpl<out HttpClientBuilderT : BaseHttpClientBuilder<HttpClientBuilderT>>(
    protected val baseBuilderState: BaseHttpClientBuilderState
) : BaseHttpClientBuilder<HttpClientBuilderT> {

    companion object : Logging {
        private const val DEFAULT_IO_THREADS_COUNT = 1
    }

    private lateinit var clientName: String

    internal val lazyClientName: () -> String
        get() = baseBuilderState.clientNameCreator

    protected abstract fun copyOfThisBuilder(
        baseBuilderStateBase: BaseHttpClientBuilderState = this.baseBuilderState
    ): HttpClientBuilderT

    override fun setClientName(clientName: String) = copyOfThisBuilder(
        baseBuilderState.copy(clientNameCreator = { clientName })
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

        val endpointGroup = baseBuilderState.endpointGroupCreator()

        val webClientBuilder = when (endpointGroup) {
            is Either.Left -> WebClient.builder(baseBuilderState.sessionProtocol, endpointGroup.value.value)
            is Either.Right -> WebClient.builder(endpointGroup.value)
            else -> WebClient.builder()
        }

        val clientFactory: ClientFactory = if (baseBuilderState.clientFactory == null) {
            // build ClientFactory
            var clientFactoryBuilder: ClientFactoryBuilder = baseBuilderState.clientFactoryBuilder()
            clientFactoryBuilder = clientFactoryBuilder.workerGroup(
                EventLoopGroups.newEventLoopGroup(
                    baseBuilderState.ioThreadsCount ?: DEFAULT_IO_THREADS_COUNT,
                    "$clientName-eventloop"
                ),
                true
            )
            var finalClientFactoryBuilder = clientFactoryBuilder
            for (customizer in baseBuilderState.clientFactoryBuilderCustomizers) {
                finalClientFactoryBuilder = finalClientFactoryBuilder.customizer()
            }
            finalClientFactoryBuilder.build()
        } else {
            // use passed ClientFactory
            baseBuilderState.clientFactory
        }


        // build ClientOptions
        var clientOptionsBuilder: ClientOptionsBuilder = baseBuilderState.clientOptionsBuilder()
        val (enrichedClientOptionsBuilder, closeableDecorators) = clientOptionsBuilder.enrichClientOptionsBuilder()
        clientOptionsBuilder = enrichedClientOptionsBuilder

        var finalClientOptionsBuilder = clientOptionsBuilder
        for (customizer in baseBuilderState.clientOptionsBuilderCustomizers) {
            finalClientOptionsBuilder = finalClientOptionsBuilder.customizer()
        }
        val clientOptions = finalClientOptionsBuilder.build()


        return CloseableWebClient(
            webClientBuilder
                .factory(clientFactory)
                .options(clientOptions)
                .build()
        ) {
            logger.info { """Closing Armeria http client "$clientName"...""" }
            val closingTimeMillis = measureTimeMillis {
                for (closeableDecorator in closeableDecorators) {
                    logger.info { "$clientName: closing decorator $closeableDecorator ..." }
                    closeableDecorator.close()
                }
                logger.info { "$clientName: closing http client factory under the hood..." }
                clientFactory.close()
                endpointGroup?.leftOrNull?.let {
                    logger.info { "$clientName: closing endpoint (group)..." }
                    it.value.close()
                }
            }
            logger.info { """Armeria http client "$clientName" closed in $closingTimeMillis ms.""" }
        }
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

    override fun setClientFactory(clientFactory: ClientFactory): HttpClientBuilderT {
        if (baseBuilderState.clientFactoryBuilderCustomizers.isNotEmpty()) {
            logger.warn {
                """ClientFactory is set when there are some clientFactoryBuilderCustomizers specified:
                    |- current builder state: $baseBuilderState
                    |- provided clientFactory: $clientFactory
                """.trimMargin()
            }
        }
        return copyOfThisBuilder(
            baseBuilderState.copy(clientFactory = clientFactory)
        )
    }

    override fun customizeArmeriaClientFactoryBuilder(
        customizer: ClientFactoryBuilderCustomizer
    ): HttpClientBuilderT {
        logWarnIfClientFactoryIsSpecified(customizer)
        return copyOfThisBuilder(
            baseBuilderState.copy(clientFactoryBuilderCustomizers = listOf(customizer))
        )
    }

    override fun addClientFactoryBuilderCustomizer(
        customizer: ClientFactoryBuilderCustomizer
    ): HttpClientBuilderT {
        logWarnIfClientFactoryIsSpecified(customizer)
        return copyOfThisBuilder(
            baseBuilderState.copy(
                clientFactoryBuilderCustomizers = baseBuilderState.clientFactoryBuilderCustomizers + customizer
            )
        )
    }

    override fun customizeArmeriaClientOptionsBuilder(
        customizer: ClientOptionsBuilderCustomizer
    ): HttpClientBuilderT = copyOfThisBuilder(
        baseBuilderState.copy(clientOptionsBuilderCustomizers = listOf(customizer))
    )

    override fun addClientOptionsBuilderCustomizer(
        customizer: ClientOptionsBuilderCustomizer
    ): HttpClientBuilderT = copyOfThisBuilder(
        baseBuilderState.copy(
            clientOptionsBuilderCustomizers = baseBuilderState.clientOptionsBuilderCustomizers + customizer
        )
    )

    private fun logWarnIfClientFactoryIsSpecified(customizer: ClientFactoryBuilderCustomizer) {
        if (baseBuilderState.clientFactory != null) {
            logger.warn {
                """ClientFactoryBuilder customizer will be ignored due to presence of clientFactory:
                        |- current builder state: $baseBuilderState
                        |- provided customizer: $customizer
                    """.trimMargin()
            }
        }
    }

    override fun toString(): String {
        return "HttpClientBuilder(clientName=${if (this::clientName.isInitialized) clientName else "not_used_client"})"
    }

}