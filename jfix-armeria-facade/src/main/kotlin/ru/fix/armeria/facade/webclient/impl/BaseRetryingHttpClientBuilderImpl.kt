package ru.fix.armeria.facade.webclient.impl

import com.linecorp.armeria.client.ClientOptionsBuilder
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.client.retry.RetryingClient
import ru.fix.aggregating.profiler.PrefixedProfiler
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.aggregating.profiler.ProfiledHttpClient
import ru.fix.armeria.commons.AutoCloseableHttpClient
import ru.fix.armeria.dynamic.request.options.DynamicRequestOptionsClient
import ru.fix.armeria.facade.Either
import ru.fix.armeria.facade.Metrics
import ru.fix.armeria.facade.webclient.BaseRetryingHttpClientBuilder
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration
import java.util.function.Function

internal data class BaseRetryingHttpClientBuilderState(
    val maxTotalAttempts: Int,
    val retryRule: RetryRule,
    val useRetryAfter: Boolean = false,
    val eachAttemptProfilingDecoratorCreator: (() -> Function<HttpClient, HttpClient>)? = null,
    val wholeRequestProfilingDecoratorCreator: (() -> Function<HttpClient, HttpClient>)? = null,
    val eachAttemptWriteRequestTimeout: Either<Duration, DynamicProperty<Duration>>? = null
)

internal abstract class BaseRetryingHttpClientBuilderImpl<BuilderT : BaseRetryingHttpClientBuilder<BuilderT>>(
    baseBuilderStateBase: BaseHttpClientBuilderState,
    protected val baseRetryingBuilderState: BaseRetryingHttpClientBuilderState
) : BaseRetryingHttpClientBuilder<BuilderT>, BaseHttpClientBuilderImpl<BuilderT>(baseBuilderStateBase) {

    override fun enableSupportOfRetryAfterHeader(): BuilderT = copyOfThisBuilder(
        baseRetryingBuilderState = baseRetryingBuilderState.copy(useRetryAfter = true)
    )

    override fun enableEachAttemptProfiling(profiler: Profiler): BuilderT = copyOfThisBuilder(
        baseRetryingBuilderState = baseRetryingBuilderState.copy(eachAttemptProfilingDecoratorCreator = {
            ProfiledHttpClient.newDecorator(
                PrefixedProfiler(
                    profiler,
                    "${baseBuilderState.clientNameCreator()}.${Metrics.EACH_RETRY_ATTEMPT_PREFIX}"
                )
            )
        })
    )

    override fun enableWholeRequestProfiling(profiler: Profiler): BuilderT = copyOfThisBuilder(
        baseRetryingBuilderState = baseRetryingBuilderState.copy(wholeRequestProfilingDecoratorCreator = {
            ProfiledHttpClient.newDecorator(
                PrefixedProfiler(
                    profiler,
                    "${baseBuilderState.clientNameCreator()}.${Metrics.WHOLE_RETRY_SESSION_PREFIX}"
                )
            )
        })
    )

    override fun setEachAttemptWriteRequestTimeout(timeout: Duration): BuilderT = copyOfThisBuilder(
        baseRetryingBuilderState = baseRetryingBuilderState.copy(
            eachAttemptWriteRequestTimeout = Either.Left(timeout)
        )
    )

    override fun setEachAttemptWriteRequestTimeout(timeoutProperty: DynamicProperty<Duration>): BuilderT =
        copyOfThisBuilder(
            baseRetryingBuilderState = baseRetryingBuilderState.copy(
                eachAttemptWriteRequestTimeout = Either.Right(timeoutProperty)
            )
        )

    override fun ClientOptionsBuilder.enrichClientOptionsBuilder()
            : Pair<ClientOptionsBuilder, List<AutoCloseableHttpClient<*>>> {
        val closeableDecorators: MutableList<AutoCloseableHttpClient<*>> = mutableListOf()

        val clientOptionsBuilder = this
            .withEachAttemptWriteRequestTimeout()
            .withEachAttemptProfilingAndRateLimitingDecorators(closeableDecorators)
            .withDefaultTimeoutsRetryingDecorator()
            .withWholeRequestProfilingDecorator()
        return clientOptionsBuilder to closeableDecorators
    }

    protected fun ClientOptionsBuilder.withEachAttemptWriteRequestTimeout(): ClientOptionsBuilder =
        this.let { optionsBuilder ->
            baseRetryingBuilderState.eachAttemptWriteRequestTimeout?.let {
                when (it) {
                    is Either.Left -> optionsBuilder.writeTimeout(it.value)
                    is Either.Right -> optionsBuilder
                        .decorator(
                            DynamicRequestOptionsClient.newHttpDecoratorWithWriteTimeout(it.value)
                        )
                }
            } ?: optionsBuilder
        }

    protected fun ClientOptionsBuilder.withEachAttemptProfilingAndRateLimitingDecorators(
        closeableDecorators: MutableList<AutoCloseableHttpClient<*>>
    ) = this.let { optionsBuilder ->
        baseRetryingBuilderState.eachAttemptProfilingDecoratorCreator?.let {
            optionsBuilder.decorator(it())
        } ?: optionsBuilder
    }.withRateLimitingDecorator(closeableDecorators)

    protected fun ClientOptionsBuilder.withDefaultTimeoutsRetryingDecorator(): ClientOptionsBuilder =
        this.decorator(
            RetryingClient.builder(baseRetryingBuilderState.retryRule)
                .maxTotalAttempts(baseRetryingBuilderState.maxTotalAttempts)
                .useRetryAfter(baseRetryingBuilderState.useRetryAfter)
                .newDecorator()
        )

    protected fun ClientOptionsBuilder.withWholeRequestProfilingDecorator(): ClientOptionsBuilder =
        this.let { optionsBuilder ->
            baseRetryingBuilderState.wholeRequestProfilingDecoratorCreator?.let {
                optionsBuilder.decorator(it())
            } ?: optionsBuilder
        }

    override fun copyOfThisBuilder(baseBuilderStateBase: BaseHttpClientBuilderState): BuilderT =
        copyOfThisBuilder(baseRetryingBuilderState, baseBuilderStateBase)

    protected abstract fun copyOfThisBuilder(
        baseRetryingBuilderState: BaseRetryingHttpClientBuilderState = this.baseRetryingBuilderState,
        baseBuilderStateBase: BaseHttpClientBuilderState = this.baseBuilderState
    ): BuilderT

}