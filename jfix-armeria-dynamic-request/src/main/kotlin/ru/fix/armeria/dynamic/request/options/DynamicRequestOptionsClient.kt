package ru.fix.armeria.dynamic.request.options

import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.SimpleDecoratingClient
import com.linecorp.armeria.common.Request
import com.linecorp.armeria.common.Response
import ru.fix.armeria.commons.asHttpClient
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration
import java.util.function.Function

/**
 * Decorating client, purposed for setting request's timeout options
 * based on current values of [DynamicProperty] properties.
 *
 * @property readTimeoutProperty property with value of [ClientRequestContext.responseTimeoutMillis]
 * @property writeTimeoutProperty property with value of [ClientRequestContext.writeTimeoutMillis]
 */
class DynamicRequestOptionsClient<RequestT : Request, ResponseT : Response> private constructor(
    delegate: Client<RequestT, ResponseT>,
    private val readTimeoutProperty: DynamicProperty<Duration>? = null,
    private val writeTimeoutProperty: DynamicProperty<Duration>? = null
) : SimpleDecoratingClient<RequestT, ResponseT>(delegate) {

    override fun execute(ctx: ClientRequestContext, req: RequestT): ResponseT {
        if (writeTimeoutProperty != null) {
            ctx.setWriteTimeout(writeTimeoutProperty.get())
        }
        if (readTimeoutProperty != null) {
            ctx.setResponseTimeout(readTimeoutProperty.get())
        }
        return unwrap().execute(ctx, req)
    }

    companion object {

        @JvmStatic
        fun newHttpDecorator(
            writeTimeoutProperty: DynamicProperty<Duration>,
            readTimeoutProperty: DynamicProperty<Duration>
        ): Function<HttpClient, HttpClient> = Function {
            DynamicRequestOptionsClient(it, readTimeoutProperty, writeTimeoutProperty).asHttpClient()
        }

        @JvmStatic
        fun newHttpDecoratorWithReadTimeout(
            readTimeoutProperty: DynamicProperty<Duration>
        ): Function<HttpClient, HttpClient> = Function {
            DynamicRequestOptionsClient(it, readTimeoutProperty).asHttpClient()
        }

        @JvmStatic
        fun newHttpDecoratorWithWriteTimeout(
            writeTimeoutProperty: DynamicProperty<Duration>
        ): Function<HttpClient, HttpClient> = Function {
            DynamicRequestOptionsClient(it, null, writeTimeoutProperty).asHttpClient()
        }


    }

}
