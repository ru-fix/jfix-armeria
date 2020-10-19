package ru.fix.armeria.facade.webclient

import com.linecorp.armeria.client.ClientFactoryBuilder
import com.linecorp.armeria.client.ClientOptionsBuilder
import com.linecorp.armeria.client.endpoint.EndpointGroup
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
     * Mandatory builder methods. Client name and endpoint/endpoint_group must be formed.
     */

    fun setClientName(clientName: String): HttpClientBuilderT

    fun setIgnoreEndpoint(ignore: Boolean): HttpClientBuilderT

    fun setEndpoint(uri: String): HttpClientBuilderT = setEndpoint(URI.create(uri))
    fun setEndpoint(uri: URI): HttpClientBuilderT
    fun setEndpoint(host: String, port: Int): HttpClientBuilderT

    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setDynamicEndpoint(
        addressProperty: DynamicProperty<SocketAddress>
    ): HttpClientBuilderT
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setDynamicEndpoints(
        addressListProperty: DynamicProperty<List<SocketAddress>>
    ): HttpClientBuilderT
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setDynamicEndpoints(
        addressListProperty: DynamicProperty<List<SocketAddress>>,
        endpointSelectionStrategy: EndpointSelectionStrategy
    ): HttpClientBuilderT

    /**
     * [endpointGroup] will be closed when closing corresponding [CloseableWebClient]
     */
    fun setEndpointGroup(
        endpointGroup: EndpointGroup
    ): HttpClientBuilderT

    /*
     * END Mandatory builder methods.
     */


    fun setIoThreadsCount(count: Int): HttpClientBuilderT

    fun setConnectTimeout(duration: Duration): HttpClientBuilderT

    fun setConnectionTtl(duration: Duration): HttpClientBuilderT

    fun setUseHttp2Preface(useHttp2Preface: Boolean): HttpClientBuilderT

    /**
     * [jfix-armeria-aggregating-profiler](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-aggregating-profiler-support_ required.
     */
    fun enableConnectionsProfiling(profiler: Profiler): HttpClientBuilderT

    /**
     * [rateLimitedDispatcher] will be closed when closing corresponding [CloseableWebClient].
     *
     * [jfix-armeria-limiter](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-rate-limiter-support_ required.
     */
    fun enableRateLimit(rateLimitedDispatcher: RateLimitedDispatcher): HttpClientBuilderT

    fun buildArmeriaWebClient(): CloseableWebClient

    /**
     * [armeria-retrofit2](https://mvnrepository.com/artifact/com.linecorp.armeria/armeria-retrofit2) dependency
     * OR Gradle capability _jfix-armeria-facade-retrofit-support_ required.
     */
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

    /**
     * [jfix-armeria-aggregating-profiler](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-aggregating-profiler-support_ required.
     */
    fun enableRequestsProfiling(profiler: Profiler): NotRetryingHttpClientBuilder

    fun setResponseTimeout(timeout: Duration): NotRetryingHttpClientBuilder
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setResponseTimeout(timeoutProperty: DynamicProperty<Duration>): NotRetryingHttpClientBuilder

    fun setWriteRequestTimeout(timeout: Duration): NotRetryingHttpClientBuilder
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setWriteRequestTimeout(timeoutProperty: DynamicProperty<Duration>): NotRetryingHttpClientBuilder

}

interface BaseRetryingHttpClientBuilder<BuilderT : BaseRetryingHttpClientBuilder<BuilderT>> :
    BaseHttpClientBuilder<BuilderT> {

    fun enableSupportOfRetryAfterHeader(): BuilderT

    /**
     * [jfix-armeria-aggregating-profiler](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-aggregating-profiler-support_ required.
     */
    fun enableEachAttemptProfiling(profiler: Profiler): BuilderT

    /**
     * [jfix-armeria-aggregating-profiler](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-aggregating-profiler-support_ required.
     */
    fun enableWholeRequestProfiling(profiler: Profiler): BuilderT

    fun setEachAttemptWriteRequestTimeout(timeout: Duration): BuilderT
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setEachAttemptWriteRequestTimeout(timeoutProperty: DynamicProperty<Duration>): BuilderT

}

interface PreparingRetryingHttpClientBuilder :
    BaseRetryingHttpClientBuilder<PreparingRetryingHttpClientBuilder> {

    fun withCustomResponseTimeouts(): TimeoutsConfiguringRetryingHttpClientBuilder

    fun withDefaultResponseTimeouts(): TimeoutsImmutableRetryingHttpClientBuilder

}

interface TimeoutsImmutableRetryingHttpClientBuilder :
    BaseRetryingHttpClientBuilder<TimeoutsImmutableRetryingHttpClientBuilder>


interface TimeoutsConfiguringRetryingHttpClientBuilder :
    BaseRetryingHttpClientBuilder<TimeoutsConfiguringRetryingHttpClientBuilder> {

    fun setEachAttemptResponseTimeout(timeout: Duration): TimeoutsImmutableRetryingHttpClientBuilder
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setEachAttemptResponseTimeout(
        timeoutProp: DynamicProperty<Duration>
    ): TimeoutsImmutableRetryingHttpClientBuilder

    fun setWholeRequestTimeout(timeout: Duration): TimeoutsImmutableRetryingHttpClientBuilder
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setWholeRequestTimeout(timeoutProperty: DynamicProperty<Duration>): TimeoutsImmutableRetryingHttpClientBuilder

    fun setResponseTimeouts(
        eachAttemptTimeout: Duration,
        wholeRequestTimeout: Duration
    ): TimeoutsImmutableRetryingHttpClientBuilder
    /**
     * [jfix-armeria-dynamic-request](https://github.com/ru-fix/jfix-armeria) dependency
     * OR Gradle capability _jfix-armeria-facade-dynamic-request-support_ required.
     */
    fun setResponseTimeouts(
        eachAttemptTimeout: Duration,
        wholeRequestTimeoutProp: DynamicProperty<Duration>
    ): TimeoutsImmutableRetryingHttpClientBuilder
}