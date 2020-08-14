package ru.fix.armeria.limiter

import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.SimpleDecoratingClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.Request
import com.linecorp.armeria.common.Response
import ru.fix.armeria.commons.AutoCloseableHttpClient
import ru.fix.armeria.commons.asHttpClient
import ru.fix.stdlib.ratelimiter.RateLimitedDispatcher
import java.util.concurrent.CompletionStage
import java.util.function.Function

abstract class RateLimitingClient<RequestT : Request, ResponseT : Response>(
    delegate: Client<RequestT, ResponseT>,
    private val rateLimiterDispatcher: RateLimitedDispatcher
) : SimpleDecoratingClient<RequestT, ResponseT>(delegate), AutoCloseable {

    protected abstract fun newDeferredResponse(
        ctx: ClientRequestContext,
        resFuture: CompletionStage<ResponseT>
    ): ResponseT

    override fun execute(ctx: ClientRequestContext, req: RequestT): ResponseT {
        val deferredResult = rateLimiterDispatcher.compose(
            {
                unwrap().execute(ctx, req)
            },
            { response, asyncResultCallback ->
                response.whenComplete().handle { _, _ -> asyncResultCallback.onAsyncResultCompleted() }
            }
        )
        return newDeferredResponse(ctx, deferredResult)
    }

    override fun close() {
        rateLimiterDispatcher.close()
    }
}

class RateLimitingHttpClient(
    delegate: Client<HttpRequest, HttpResponse>,
    rateLimiterDispatcher: RateLimitedDispatcher
) : RateLimitingClient<HttpRequest, HttpResponse>(delegate, rateLimiterDispatcher) {

    override fun newDeferredResponse(
        ctx: ClientRequestContext,
        resFuture: CompletionStage<HttpResponse>
    ): HttpResponse = HttpResponse.from(resFuture, ctx.eventLoop())

    companion object {
        @JvmStatic
        fun newDecorator(rateLimiterDispatcher: RateLimitedDispatcher)
                : Function<HttpClient, AutoCloseableHttpClient<*>> = Function {
            RateLimitingHttpClient(it, rateLimiterDispatcher).asHttpClient()
        }
    }
}