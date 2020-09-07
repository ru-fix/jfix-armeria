package ru.fix.armeria.facade.webclient.impl

import com.linecorp.armeria.client.ClientOptionsBuilder
import com.linecorp.armeria.client.HttpClient
import ru.fix.aggregating.profiler.PrefixedProfiler
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClient
import ru.fix.armeria.commons.AutoCloseableHttpClient
import ru.fix.armeria.dynamic.request.options.DynamicRequestOptionsClient
import ru.fix.armeria.facade.Either
import ru.fix.armeria.facade.webclient.NotRetryingHttpClientBuilder
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration
import java.util.function.Function


internal data class NotRetryingHttpClientBuilderState(
    val profilingDecoratorCreator: (() -> Function<HttpClient, HttpClient>)? = null,
    val responseTimeout: Either<Duration, DynamicProperty<Duration>>? = null,
    val writeRequestTimeout: Either<Duration, DynamicProperty<Duration>>? = null
)

internal class NotRetryingHttpClientBuilderImpl(
    baseBuilderStateBase: BaseHttpClientBuilderState,
    private val notRetryingBuilderState: NotRetryingHttpClientBuilderState = NotRetryingHttpClientBuilderState()
) : NotRetryingHttpClientBuilder, BaseHttpClientBuilderImpl<NotRetryingHttpClientBuilder>(baseBuilderStateBase) {


    override fun enableRequestsProfiling(profiler: Profiler): NotRetryingHttpClientBuilder =
        NotRetryingHttpClientBuilderImpl(
            baseBuilderState,
            notRetryingBuilderState.copy(
                profilingDecoratorCreator = {
                    ProfiledHttpClient.newDecorator(
                        PrefixedProfiler(
                            profiler,
                            baseBuilderState.clientNameCreator()
                        )
                    )
                }
            )
        )

    override fun setResponseTimeout(timeout: Duration): NotRetryingHttpClientBuilder =
        NotRetryingHttpClientBuilderImpl(
            baseBuilderState,
            notRetryingBuilderState.copy(responseTimeout = Either.Left(timeout))
        )

    override fun setResponseTimeout(timeoutProperty: DynamicProperty<Duration>): NotRetryingHttpClientBuilder =
        NotRetryingHttpClientBuilderImpl(
            baseBuilderState,
            notRetryingBuilderState.copy(responseTimeout = Either.Right(timeoutProperty))
        )

    override fun setWriteRequestTimeout(timeout: Duration): NotRetryingHttpClientBuilder =
        NotRetryingHttpClientBuilderImpl(
            baseBuilderState,
            notRetryingBuilderState.copy(writeRequestTimeout = Either.Left(timeout))
        )

    override fun setWriteRequestTimeout(timeoutProperty: DynamicProperty<Duration>): NotRetryingHttpClientBuilder =
        NotRetryingHttpClientBuilderImpl(
            baseBuilderState,
            notRetryingBuilderState.copy(writeRequestTimeout = Either.Right(timeoutProperty))
        )

    override fun ClientOptionsBuilder.enrichClientOptionsBuilder()
            : Pair<ClientOptionsBuilder, List<AutoCloseableHttpClient<*>>> {
        val closeableDecorators = mutableListOf<AutoCloseableHttpClient<*>>()

        val clientOptionsBuilder = this
            .let { optionsBuilder ->
                when (notRetryingBuilderState.writeRequestTimeout) {
                    is Either.Left -> optionsBuilder
                        .writeTimeout(notRetryingBuilderState.writeRequestTimeout.value)
                        .let {
                            when (notRetryingBuilderState.responseTimeout) {
                                is Either.Left -> it
                                    .responseTimeout(notRetryingBuilderState.responseTimeout.value)
                                is Either.Right -> it
                                    .decorator(DynamicRequestOptionsClient.newHttpDecoratorWithReadTimeout(
                                        notRetryingBuilderState.responseTimeout.value
                                    ))
                                null -> it
                            }
                        }
                    is Either.Right -> when (notRetryingBuilderState.responseTimeout) {
                        is Either.Left -> optionsBuilder
                            .responseTimeout(notRetryingBuilderState.responseTimeout.value)
                            .decorator(DynamicRequestOptionsClient.newHttpDecoratorWithWriteTimeout(
                                notRetryingBuilderState.writeRequestTimeout.value
                            ))
                        is Either.Right -> optionsBuilder
                            .decorator(DynamicRequestOptionsClient.newHttpDecorator(
                                notRetryingBuilderState.writeRequestTimeout.value,
                                notRetryingBuilderState.responseTimeout.value
                            ))
                        null -> optionsBuilder
                            .decorator(DynamicRequestOptionsClient.newHttpDecoratorWithWriteTimeout(
                                notRetryingBuilderState.writeRequestTimeout.value
                            ))
                    }
                    null -> when (notRetryingBuilderState.responseTimeout) {
                        is Either.Left -> optionsBuilder
                            .responseTimeout(notRetryingBuilderState.responseTimeout.value)
                        is Either.Right -> optionsBuilder
                            .decorator(DynamicRequestOptionsClient.newHttpDecoratorWithReadTimeout(
                                notRetryingBuilderState.responseTimeout.value
                            ))
                        null -> optionsBuilder
                    }
                }
            }
            .let { optionsBuilder ->
                notRetryingBuilderState.profilingDecoratorCreator?.let {
                    optionsBuilder.decorator(it())
                } ?: optionsBuilder
            }.withRateLimitingDecorator(closeableDecorators)

        return clientOptionsBuilder to closeableDecorators
    }


    override fun copyOfThisBuilder(baseBuilderStateBase: BaseHttpClientBuilderState): NotRetryingHttpClientBuilder =
        NotRetryingHttpClientBuilderImpl(baseBuilderStateBase, notRetryingBuilderState)

}

