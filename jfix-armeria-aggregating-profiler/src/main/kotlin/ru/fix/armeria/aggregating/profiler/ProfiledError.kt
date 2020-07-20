package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ResponseTimeoutException
import com.linecorp.armeria.client.SessionProtocolNegotiationException
import com.linecorp.armeria.client.endpoint.EndpointGroupException
import com.linecorp.armeria.common.ClosedSessionException
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.HttpStatusClass
import com.linecorp.armeria.common.logging.RequestLog
import com.linecorp.armeria.common.stream.ClosedStreamException
import io.netty.handler.codec.http2.Http2Error
import io.netty.handler.codec.http2.Http2Exception
import ru.fix.armeria.commons.unwrapUnprocessedExceptionIfNecessary
import java.net.ConnectException

internal sealed class ProfiledError(
    val latencyMetricRequired: Boolean = false,
    typeMetricName: String,
    vararg additionalMetrics: MetricTag
) {

    val metricTags: List<MetricTag> = listOf(
        (MetricTags.ERROR_TYPE to typeMetricName),
        *additionalMetrics
    )

    object ConnectRefused : ProfiledError(latencyMetricRequired = true, typeMetricName = "connect_refused")
    object ConnectTimeout : ProfiledError(typeMetricName = "connect_timeout")
    object NoAvailableEndpoint : ProfiledError(typeMetricName = "no_available_endpoint")
    object RequestClosedSession: ProfiledError(
        latencyMetricRequired = true,
        typeMetricName = "request_closed_session"
    )

    class Http2ErrorOccurred(http2Error: Http2Error) : ProfiledError(
        typeMetricName = "http2_error",
        additionalMetrics = *arrayOf("http2_error_type" to http2Error.name)
    )

    object ResponseTimeout : ProfiledError(typeMetricName = "response_timeout")
    object ResponseClosedSession : ProfiledError(typeMetricName = "response_closed_session")
    object ResponseClosedStream : ProfiledError(typeMetricName = "response_closed_stream")
    object InvalidStatus : ProfiledError(latencyMetricRequired = true, typeMetricName = "invalid_status")

    sealed class UnrecognizedError(
        latencyMetricRequired: Boolean = false,
        typeMetricName: String
    ) : ProfiledError(latencyMetricRequired, typeMetricName) {

        object UnknownStatus : UnrecognizedError(latencyMetricRequired = true, typeMetricName = "unknown_status")

        sealed class WithCause(latencyMetricRequired: Boolean = false, typeMetricName: String, val cause: Throwable) :
            UnrecognizedError(latencyMetricRequired, typeMetricName) {

            class InRequest(cause: Throwable) : WithCause(typeMetricName = "unrecognized_request_error", cause = cause)

            class InResponse(cause: Throwable) : WithCause(
                typeMetricName = "unrecognized_response_error",
                cause = cause
            )
        }

    }
}

internal fun RequestLog.detectProfiledErrorIfAny(): ProfiledError? {
    val requestCause = requestCause()
    val responseCause = responseCause()
    val status = responseHeaders().status()
    return when {
        status.codeClass() != HttpStatusClass.SUCCESS && status != HttpStatus.UNKNOWN -> ProfiledError.InvalidStatus
        requestCause != null -> requestCause.unwrapUnprocessedExceptionIfNecessary().let {
            when (it) {
                is ConnectException -> ProfiledError.ConnectRefused
                is SessionProtocolNegotiationException -> ProfiledError.ConnectTimeout
                is EndpointGroupException -> ProfiledError.NoAvailableEndpoint
                is ClosedSessionException -> ProfiledError.RequestClosedSession
                is Http2Exception -> ProfiledError.Http2ErrorOccurred(it.error())
                else -> {
                    ProfiledError.UnrecognizedError.WithCause.InRequest(requestCause)
                }
            }
        }
        responseCause != null ->
            when (responseCause) {
                is ResponseTimeoutException -> ProfiledError.ResponseTimeout
                is ClosedSessionException -> ProfiledError.ResponseClosedSession
                is ClosedStreamException -> ProfiledError.ResponseClosedStream
                else -> {
                    ProfiledError.UnrecognizedError.WithCause.InResponse(responseCause)
                }
            }
        status == HttpStatus.UNKNOWN -> {
            ProfiledError.UnrecognizedError.UnknownStatus
        }
        else -> null
    }
}
