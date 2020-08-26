package ru.fix.armeria.facade.webclient.impl

import ru.fix.armeria.facade.webclient.PreparingRetryingHttpClientBuilder
import ru.fix.armeria.facade.webclient.TimeoutsConfiguringRetryingHttpClientBuilder
import ru.fix.armeria.facade.webclient.TimeoutsImmutableRetryingHttpClientBuilder

internal class PreparingRetryingHttpClientBuilderImpl(
    baseRetryingBuilderState: BaseRetryingHttpClientBuilderState,
    baseBuilderStateBase: BaseHttpClientBuilderState
) : PreparingRetryingHttpClientBuilder,
    BaseRetryingHttpClientBuilderImpl<PreparingRetryingHttpClientBuilder>(
        baseBuilderStateBase,
        baseRetryingBuilderState
    ) {

    override fun withCustomTimeouts(): TimeoutsConfiguringRetryingHttpClientBuilder =
        TimeoutsConfiguringRetryingHttpClientBuilderImpl(baseBuilderState, baseRetryingBuilderState)

    override fun withDefaultTimeouts(): TimeoutsImmutableRetryingHttpClientBuilder =
        TimeoutsImmutableRetryingHttpClientBuilderImpl(baseBuilderState, baseRetryingBuilderState)

    override fun copyOfThisBuilder(
        baseRetryingBuilderState: BaseRetryingHttpClientBuilderState,
        baseBuilderStateBase: BaseHttpClientBuilderState
    ): PreparingRetryingHttpClientBuilder = PreparingRetryingHttpClientBuilderImpl(
        baseRetryingBuilderState,
        baseBuilderStateBase
    )
}