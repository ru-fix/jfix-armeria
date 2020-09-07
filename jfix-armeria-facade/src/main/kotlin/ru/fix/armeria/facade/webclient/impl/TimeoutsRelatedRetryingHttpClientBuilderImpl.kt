package ru.fix.armeria.facade.webclient.impl

import com.linecorp.armeria.client.ClientOptionsBuilder
import com.linecorp.armeria.client.retry.RetryingClient
import ru.fix.armeria.commons.AutoCloseableHttpClient
import ru.fix.armeria.dynamic.request.options.DynamicRequestOptionsClient
import ru.fix.armeria.facade.Either
import ru.fix.armeria.facade.webclient.TimeoutsConfiguringRetryingHttpClientBuilder
import ru.fix.armeria.facade.webclient.TimeoutsImmutableRetryingHttpClientBuilder
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration

internal sealed class RetryTimeouts {
    data class ForEachAttempt(
        val attemptTimeout: Either<Duration, DynamicProperty<Duration>>
    ) : RetryTimeouts()

    data class ForWholeRequest(
        val wholeRequestTimeout: Either<Duration, DynamicProperty<Duration>>
    ) : RetryTimeouts()

    data class ForEachAttemptAndWholeRequest(
        val attemptTimeout: Duration,
        val wholeRequestTimeout: Either<Duration, DynamicProperty<Duration>>
    ) : RetryTimeouts()
}

internal class TimeoutsImmutableRetryingHttpClientBuilderImpl(
    baseBuilderStateBase: BaseHttpClientBuilderState,
    baseRetryingBuilderState: BaseRetryingHttpClientBuilderState,
    private val retryTimeouts: RetryTimeouts? = null
) : TimeoutsImmutableRetryingHttpClientBuilder,
    BaseRetryingHttpClientBuilderImpl<TimeoutsImmutableRetryingHttpClientBuilder>(
        baseBuilderStateBase,
        baseRetryingBuilderState
    ) {

    override fun copyOfThisBuilder(
        baseRetryingBuilderState: BaseRetryingHttpClientBuilderState,
        baseBuilderStateBase: BaseHttpClientBuilderState
    ): TimeoutsImmutableRetryingHttpClientBuilder = TimeoutsImmutableRetryingHttpClientBuilderImpl(
        baseBuilderStateBase, baseRetryingBuilderState, retryTimeouts
    )

    override fun ClientOptionsBuilder.enrichClientOptionsBuilder()
            : Pair<ClientOptionsBuilder, List<AutoCloseableHttpClient<*>>> {
        val closeableDecorators: MutableList<AutoCloseableHttpClient<*>> = mutableListOf()

        val clientOptionsBuilderWithRetries = when (retryTimeouts) {
            null -> this
                .withEachAttemptProfilingAndRateLimitingDecorators(closeableDecorators)
                .withDefaultTimeoutsRetryingDecorator()
            is RetryTimeouts.ForEachAttempt -> when (val timeout = retryTimeouts.attemptTimeout) {
                is Either.Left -> this
                    .withEachAttemptProfilingAndRateLimitingDecorators(closeableDecorators)
                    .withEachAttemptTimeoutRetryingDecorator(timeout.value)
                is Either.Right -> this
                    .decorator(DynamicRequestOptionsClient.newHttpDecoratorWithReadTimeout(timeout.value))
                    .withEachAttemptProfilingAndRateLimitingDecorators(closeableDecorators)
                    .withDefaultTimeoutsRetryingDecorator()
            }
            is RetryTimeouts.ForWholeRequest -> this
                .withEachAttemptProfilingAndRateLimitingDecorators(closeableDecorators)
                .withDefaultTimeoutsRetryingDecorator()
                .withWholeRequestTimeout(retryTimeouts.wholeRequestTimeout)
            is RetryTimeouts.ForEachAttemptAndWholeRequest -> this
                .withEachAttemptProfilingAndRateLimitingDecorators(closeableDecorators)
                .withEachAttemptTimeoutRetryingDecorator(retryTimeouts.attemptTimeout)
                .withWholeRequestTimeout(retryTimeouts.wholeRequestTimeout)
        }.withWholeRequestProfilingDecorator()

        return clientOptionsBuilderWithRetries to closeableDecorators
    }

    private fun ClientOptionsBuilder.withEachAttemptTimeoutRetryingDecorator(
        timeout: Duration
    ): ClientOptionsBuilder =
        this.decorator(
            RetryingClient.builder(baseRetryingBuilderState.retryRule)
                .responseTimeoutForEachAttempt(timeout)
                .maxTotalAttempts(baseRetryingBuilderState.maxTotalAttempts)
                .useRetryAfter(baseRetryingBuilderState.useRetryAfter)
                .newDecorator()
        )

    private fun ClientOptionsBuilder.withWholeRequestTimeout(
        timeout: Either<Duration, DynamicProperty<Duration>>
    ): ClientOptionsBuilder = when (timeout) {
        is Either.Left -> this
            .responseTimeout(timeout.value)
        is Either.Right -> this
            .decorator(DynamicRequestOptionsClient.newHttpDecoratorWithReadTimeout(timeout.value))
    }

}

internal class TimeoutsConfiguringRetryingHttpClientBuilderImpl(
    baseBuilderStateBase: BaseHttpClientBuilderState,
    baseRetryingBuilderState: BaseRetryingHttpClientBuilderState
) : TimeoutsConfiguringRetryingHttpClientBuilder,
    BaseRetryingHttpClientBuilderImpl<TimeoutsConfiguringRetryingHttpClientBuilder>(
        baseBuilderStateBase, baseRetryingBuilderState
    ) {

    override fun setEachAttemptResponseTimeout(timeout: Duration): TimeoutsImmutableRetryingHttpClientBuilder =
        TimeoutsImmutableRetryingHttpClientBuilderImpl(
            baseBuilderState, baseRetryingBuilderState, RetryTimeouts.ForEachAttempt(Either.Left(timeout))
        )

    override fun setEachAttemptResponseTimeout(
        timeoutProp: DynamicProperty<Duration>
    ): TimeoutsImmutableRetryingHttpClientBuilder = TimeoutsImmutableRetryingHttpClientBuilderImpl(
        baseBuilderState, baseRetryingBuilderState,
        RetryTimeouts.ForEachAttempt(Either.Right(timeoutProp))
    )

    override fun setWholeRequestTimeout(
        timeout: Duration
    ): TimeoutsImmutableRetryingHttpClientBuilder = TimeoutsImmutableRetryingHttpClientBuilderImpl(
        baseBuilderState, baseRetryingBuilderState,
        RetryTimeouts.ForWholeRequest(Either.Left(timeout))
    )

    override fun setWholeRequestTimeout(
        timeoutProperty: DynamicProperty<Duration>
    ): TimeoutsImmutableRetryingHttpClientBuilder = TimeoutsImmutableRetryingHttpClientBuilderImpl(
        baseBuilderState, baseRetryingBuilderState,
        RetryTimeouts.ForWholeRequest(Either.Right(timeoutProperty))
    )

    override fun setResponseTimeouts(
        eachAttemptTimeout: Duration,
        wholeRequestTimeout: Duration
    ): TimeoutsImmutableRetryingHttpClientBuilder = TimeoutsImmutableRetryingHttpClientBuilderImpl(
        baseBuilderState, baseRetryingBuilderState,
        RetryTimeouts.ForEachAttemptAndWholeRequest(
            eachAttemptTimeout,
            Either.Left(eachAttemptTimeout)
        )
    )

    override fun setResponseTimeouts(
        eachAttemptTimeout: Duration,
        wholeRequestTimeoutProp: DynamicProperty<Duration>
    ): TimeoutsImmutableRetryingHttpClientBuilder = TimeoutsImmutableRetryingHttpClientBuilderImpl(
        baseBuilderState, baseRetryingBuilderState,
        RetryTimeouts.ForEachAttemptAndWholeRequest(
            eachAttemptTimeout,
            Either.Right(wholeRequestTimeoutProp)
        )
    )

    override fun copyOfThisBuilder(
        baseRetryingBuilderState: BaseRetryingHttpClientBuilderState,
        baseBuilderStateBase: BaseHttpClientBuilderState
    ): TimeoutsConfiguringRetryingHttpClientBuilder =
        TimeoutsConfiguringRetryingHttpClientBuilderImpl(baseBuilderStateBase, baseRetryingBuilderState)
}
