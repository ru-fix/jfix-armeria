package ru.fix.armeria.facade.webclient

import com.linecorp.armeria.client.ClientFactoryBuilder
import com.linecorp.armeria.client.ClientOptionsBuilder
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy
import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.common.SessionProtocol
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.commons.On503AndUnprocessedRetryRule
import ru.fix.armeria.dynamic.request.endpoint.SocketAddress
import ru.fix.armeria.facade.retrofit.RetrofitHttpClientBuilder
import ru.fix.dynamic.property.api.DynamicProperty
import ru.fix.stdlib.ratelimiter.RateLimitedDispatcher
import java.net.URI
import java.time.Duration

interface BaseHttpClientBuilder<out HttpClientBuilderT : BaseHttpClientBuilder<HttpClientBuilderT>> {

    /*
     * Mandatory builder methods. endpoint or endpoint group must be formed
     */

    fun setClientName(clientName: String): HttpClientBuilderT

    fun setEndpoint(uri: String): HttpClientBuilderT = setEndpoint(URI.create(uri))
    fun setEndpoint(uri: URI): HttpClientBuilderT
    fun setEndpoint(host: String, port: Int): HttpClientBuilderT

    fun setDynamicEndpoint(
        addressProperty: DynamicProperty<SocketAddress>
    ): HttpClientBuilderT

    fun setDynamicEndpoints(
        addressListProperty: DynamicProperty<List<SocketAddress>>
    ): HttpClientBuilderT

    fun setDynamicEndpoints(
        addressListProperty: DynamicProperty<List<SocketAddress>>,
        endpointSelectionStrategy: EndpointSelectionStrategy
    ): HttpClientBuilderT

    /*
     * END Mandatory builder methods.
     */


    fun setIoThreadsCount(count: Int): HttpClientBuilderT

    fun setConnectTimeout(duration: Duration): HttpClientBuilderT

    fun setConnectionTtl(duration: Duration): HttpClientBuilderT

    fun setUseHttp2Preface(useHttp2Preface: Boolean): HttpClientBuilderT

    fun enableConnectionsProfiling(profiler: Profiler): HttpClientBuilderT

    fun enableRateLimit(rateLimitedDispatcher: RateLimitedDispatcher): HttpClientBuilderT

    fun buildArmeriaWebClient(): CloseableWebClient

    fun enableRetrofitSupport(): RetrofitHttpClientBuilder


    /*
     Low-level Armeria configuration methods.
     BE AWARE that they always applied at the end of building and can override some options,
     set by other builder's methods.
     */

    fun setSessionProtocol(sessionProtocol: SessionProtocol): HttpClientBuilderT

    fun customizeArmeriaClientFactoryBuilder(
        customizer: ClientFactoryBuilder.() -> ClientFactoryBuilder
    ): HttpClientBuilderT

    fun customizeArmeriaClientOptionsBuilder(
        customizer: ClientOptionsBuilder.() -> ClientOptionsBuilder
    ): HttpClientBuilderT

}

interface PreparingHttpClientBuilder : BaseHttpClientBuilder<PreparingHttpClientBuilder> {

    fun withRetries(maxTotalAttempts: Int, retryRule: RetryRule): PreparingRetryingHttpClientBuilder

    fun withRetriesOn503AndUnprocessedError(maxTotalAttempts: Int): PreparingRetryingHttpClientBuilder =
        withRetries(maxTotalAttempts, On503AndUnprocessedRetryRule)

    fun withoutRetries(): NotRetryingHttpClientBuilder

}

interface NotRetryingHttpClientBuilder : BaseHttpClientBuilder<NotRetryingHttpClientBuilder> {


    fun enableRequestsProfiling(profiler: Profiler): NotRetryingHttpClientBuilder

    fun setResponseTimeout(timeout: Duration): NotRetryingHttpClientBuilder
    fun setResponseTimeout(timeoutProperty: DynamicProperty<Duration>): NotRetryingHttpClientBuilder

    fun setWriteRequestTimeout(timeout: Duration): NotRetryingHttpClientBuilder
    fun setWriteRequestTimeout(timeoutProperty: DynamicProperty<Duration>): NotRetryingHttpClientBuilder

}

interface BaseRetryingHttpClientBuilder<BuilderT : BaseRetryingHttpClientBuilder<BuilderT>> :
    BaseHttpClientBuilder<BuilderT> {

    fun enableSupportOfRetryAfterHeader(): BuilderT

    fun enableEachAttemptProfiling(profiler: Profiler): BuilderT

    fun enableWholeRequestProfiling(profiler: Profiler): BuilderT

}

interface PreparingRetryingHttpClientBuilder :
    BaseRetryingHttpClientBuilder<PreparingRetryingHttpClientBuilder> {

    fun withCustomTimeouts(): TimeoutsConfiguringRetryingHttpClientBuilder

    fun withDefaultTimeouts(): TimeoutsImmutableRetryingHttpClientBuilder

}

interface TimeoutsImmutableRetryingHttpClientBuilder :
    BaseRetryingHttpClientBuilder<TimeoutsImmutableRetryingHttpClientBuilder>


interface TimeoutsConfiguringRetryingHttpClientBuilder :
    BaseRetryingHttpClientBuilder<TimeoutsConfiguringRetryingHttpClientBuilder> {

    fun setEachAttemptResponseTimeout(timeout: Duration): TimeoutsImmutableRetryingHttpClientBuilder
    fun setEachAttemptResponseTimeout(
        timeoutProp: DynamicProperty<Duration>
    ): TimeoutsImmutableRetryingHttpClientBuilder

    fun setWholeRequestTimeout(timeout: Duration): TimeoutsImmutableRetryingHttpClientBuilder
    fun setWholeRequestTimeout(timeoutProperty: DynamicProperty<Duration>): TimeoutsImmutableRetryingHttpClientBuilder

    fun setResponseTimeouts(
        eachAttemptTimeout: Duration,
        wholeRequestTimeout: Duration
    ): TimeoutsImmutableRetryingHttpClientBuilder
    fun setResponseTimeouts(
        eachAttemptTimeout: Duration,
        wholeRequestTimeoutProp: DynamicProperty<Duration>
    ): TimeoutsImmutableRetryingHttpClientBuilder
}