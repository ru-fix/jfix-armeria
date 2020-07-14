package ru.fix.armeria.dynamic.request.options

import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.SimpleDecoratingClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.Request
import com.linecorp.armeria.common.Response
import ru.fix.dynamic.property.api.DynamicProperty
import java.util.function.Function

/**
 * Decorating client, purposed for setting request's timeout options
 * based on current values of [DynamicProperty] properties.
 *
 * @property readTimeoutProperty property with value of [ClientRequestContext.responseTimeoutMillis]
 * @property writeTimeoutProperty property with value of [ClientRequestContext.writeTimeoutMillis]
 */
class DynamicRequestOptionsClient<RequestT : Request, ResponseT : Response>(
    delegate: Client<RequestT, ResponseT>,
    private val readTimeoutProperty: DynamicProperty<Long>,
    private val writeTimeoutProperty: DynamicProperty<Long>
) : SimpleDecoratingClient<RequestT, ResponseT>(delegate) {

    override fun execute(ctx: ClientRequestContext, req: RequestT): ResponseT {
        ctx.setWriteTimeoutMillis(writeTimeoutProperty.get())
        ctx.setResponseTimeoutMillis(readTimeoutProperty.get())
        return delegate<Client<RequestT, ResponseT>>().execute(ctx, req)
    }

    companion object {

        private class DynamicRequestOptionsHttpClient(
            private val delegate: DynamicRequestOptionsClient<HttpRequest, HttpResponse>
        ) : HttpClient, Client<HttpRequest, HttpResponse> by delegate

        @JvmStatic
        fun newHttpDecorator(
            readTimeoutProperty: DynamicProperty<Long>,
            writeTimeoutProperty: DynamicProperty<Long>
        ): Function<HttpClient, HttpClient> = Function {
            DynamicRequestOptionsHttpClient(
                DynamicRequestOptionsClient(it, readTimeoutProperty, writeTimeoutProperty)
            )
        }
    }

}
