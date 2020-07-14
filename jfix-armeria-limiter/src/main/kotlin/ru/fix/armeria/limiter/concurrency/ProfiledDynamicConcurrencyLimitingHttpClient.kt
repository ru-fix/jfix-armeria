package ru.fix.armeria.limiter.concurrency

import MetricTags
import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.commons.asHttpClient
import ru.fix.dynamic.property.api.DynamicProperty
import java.util.concurrent.CompletionStage
import java.util.function.Function

class ProfiledDynamicConcurrencyLimitingHttpClient(
    delegate: Client<HttpRequest, HttpResponse>,
    profiler: Profiler,
    //TODO refactor properties structure since to clearly distinguish between possible combinations
    maxConcurrencyProperty: DynamicProperty<Int>,
    timeoutMsProperty: DynamicProperty<Long>
) : ProfiledDynamicConcurrencyLimitingClient<HttpRequest, HttpResponse>(
    delegate,
    profiler,
    maxConcurrencyProperty,
    timeoutMsProperty
) {
    override fun newDeferredResponse(
        ctx: ClientRequestContext,
        resultFuture: CompletionStage<HttpResponse>
    ): HttpResponse = HttpResponse.from(resultFuture)

    override fun getRequestMetricTags(ctx: ClientRequestContext, req: HttpRequest): Map<String, String> =
        MetricTags.build(
            ctx.path(),
            ctx.method()
        )

    companion object {

        @JvmStatic
        fun newDecorator(
            profiler: Profiler,
            maxConcurrencyProperty: DynamicProperty<Int>,
            timeoutMillisProperty: DynamicProperty<Long>
        ): Function<HttpClient, HttpClient> = Function {
            ProfiledDynamicConcurrencyLimitingHttpClient(
                it,
                profiler,
                maxConcurrencyProperty,
                timeoutMillisProperty
            ).asHttpClient()
        }
    }
}