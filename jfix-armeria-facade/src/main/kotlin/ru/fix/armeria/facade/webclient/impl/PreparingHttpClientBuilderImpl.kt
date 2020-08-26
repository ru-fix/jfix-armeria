package ru.fix.armeria.facade.webclient.impl

import com.linecorp.armeria.client.retry.RetryRule
import ru.fix.armeria.facade.webclient.NotRetryingHttpClientBuilder
import ru.fix.armeria.facade.webclient.PreparingHttpClientBuilder
import ru.fix.armeria.facade.webclient.PreparingRetryingHttpClientBuilder

internal class PreparingHttpClientBuilderImpl(
    baseBuilderStateBase: BaseHttpClientBuilderState = BaseHttpClientBuilderState()
) : PreparingHttpClientBuilder,
    BaseHttpClientBuilderImpl<PreparingHttpClientBuilder>(baseBuilderStateBase) {

    override fun copyOfThisBuilder(baseBuilderStateBase: BaseHttpClientBuilderState): PreparingHttpClientBuilder =
        PreparingHttpClientBuilderImpl(baseBuilderStateBase)

    override fun withRetries(maxTotalAttempts: Int, retryRule: RetryRule): PreparingRetryingHttpClientBuilder =
        PreparingRetryingHttpClientBuilderImpl(
            BaseRetryingHttpClientBuilderState(maxTotalAttempts, retryRule), baseBuilderState)

    override fun withoutRetries(): NotRetryingHttpClientBuilder = NotRetryingHttpClientBuilderImpl(baseBuilderState)

}