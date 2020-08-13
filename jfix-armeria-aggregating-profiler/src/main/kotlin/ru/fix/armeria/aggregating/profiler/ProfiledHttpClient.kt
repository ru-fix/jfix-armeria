package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.client.SimpleDecoratingHttpClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.logging.RequestLog
import com.linecorp.armeria.common.logging.RequestLogProperty
import com.linecorp.armeria.common.logging.RequestOnlyLog
import io.netty.util.AttributeKey
import org.apache.logging.log4j.kotlin.logger
import ru.fix.aggregating.profiler.Identity
import ru.fix.aggregating.profiler.ProfiledCall
import ru.fix.aggregating.profiler.Profiler
import ru.fix.armeria.commons.endpointInetSockedAddress
import ru.fix.armeria.commons.knownResponseStatus
import ru.fix.armeria.commons.sessionProtocol
import java.util.function.Function

class ProfiledHttpClient private constructor(delegate: HttpClient, private val profiler: Profiler) :
    SimpleDecoratingHttpClient(delegate) {

    override fun execute(ctx: ClientRequestContext, req: HttpRequest): HttpResponse {

        startProfiledCallBeforeConnectionEstablished(req, ctx)

        // NOTE - if retries enabled (Retrying decorator placed after this on),
        // then it is called after connection to endpoint of 1st attempt  (log.children() contains only 1 element)
        ctx.log().whenAvailable(RequestLogProperty.SESSION).thenAccept { log ->
            profileOnConnectionEstablished(req, log)
        }

        // NOTE - if retries enabled (Retrying decorator placed after this on),
        // then it is called after all attempts (log.children() contains all attempts)
        ctx.log().whenComplete().thenAccept { log ->
            profileOnRequestCompleted(req, log)
        }

        return unwrap().execute(ctx, req)
    }

    private fun startProfiledCallBeforeConnectionEstablished(req: HttpRequest, ctx: ClientRequestContext) {
        profiler.profiledCall(
            Identity(
                Metrics.HTTP_CONNECT,
                MetricTags.build(
                    path = req.path(),
                    method = req.method(),
                    protocol = ctx.sessionProtocol()
                )
            )
        ).apply {
            ctx.setAttr(HTTP_CONNECT_PROFILED_CALL, ConnectProfiledCall(this, System.currentTimeMillis()))
            start()
        }
    }

    private fun profileOnConnectionEstablished(req: HttpRequest, log: RequestOnlyLog) {
        log.context().attr(HTTP_CONNECT_PROFILED_CALL)?.let {
            it.profiledCall.stop()
            log.context().setAttr(HTTP_CONNECT_PROFILED_CALL, null)

            // If connection was unsuccessful, there is no need to profile it by "connected" metric,
            // since it will have same tag set as "connect" metric.
            if (log.channel() != null) {
                profiler.profiledCall(
                    Identity(
                        /**
                         * TODO After https://github.com/ru-fix/aggregating-profiler/issues/42 resolved,
                         *  there will be no need to write separate connected metric
                         */
                        Metrics.HTTP_CONNECTED,
                        MetricTags.build(
                            path = req.path(),
                            method = req.method(),
                            remoteInetSocketAddress = log.context().endpointInetSockedAddress,
                            protocol = log.sessionProtocol(),
                            channel = log.channel()
                        )
                    )
                /*
                 * It has been discovered by tests, that when retry decorator placed before profiling one,
                 * then connectionTimings is not available on last request. That is why we declare additional option.
                 */
                ).call(log.connectionTimings()?.connectionAcquisitionStartTimeMillis() ?: it.connectStartTimestamp)
            }
        }


    }

    private fun profileOnRequestCompleted(req: HttpRequest, log: RequestLog) {
        val profiledError: ProfiledError? = log.detectProfiledErrorIfAny()

        if (profiledError != null && profiledError is ProfiledError.UnrecognizedError) {
            when (val unrecognizedErrorType = profiledError) {
                is ProfiledError.UnrecognizedError.UnknownStatus -> {
                    logger.error {
                        "Unexpected UNKNOWN response status of request log $log"
                    }
                    return
                }
                is ProfiledError.UnrecognizedError.WithCause -> {
                    val causeSource = when (unrecognizedErrorType) {
                        is ProfiledError.UnrecognizedError.WithCause.InRequest -> "request"
                        is ProfiledError.UnrecognizedError.WithCause.InResponse -> "response"
                    }
                    logger.warn(unrecognizedErrorType.cause) { "Unrecognized $causeSource exception occurred: " }
                }
            }
        }

        val tags = MetricTags.build(
            path = req.path(),
            method = req.method(),
            remoteInetSocketAddress = log.context().endpointInetSockedAddress,
            responseStatusCode = log.knownResponseStatus,
            protocol = log.sessionProtocol,
            channel = log.channel()
        )

        when {
            profiledError != null -> {

                profiler.profiledCall(
                    Identity(Metrics.HTTP_ERROR, tags + profiledError.metricTags)
                ).let {
                    if (profiledError.latencyMetricRequired) {
                        it.call(log.requestStartTimeMillis())
                    } else {
                        it.call()
                    }
                }
            }
            else -> {

                profiler.profiledCall(
                    Identity(Metrics.HTTP_SUCCESS, tags)
                ).call(log.requestStartTimeMillis())
            }
        }

        logger.debug { "Request log = $log; connections timings = ${log.connectionTimings()}" }
    }

    companion object {

        private val logger = logger()
        private val HTTP_CONNECT_PROFILED_CALL = AttributeKey.valueOf<ConnectProfiledCall>(
            ProfiledHttpClient::class.java, "HTTP_CONNECT_PROFILED_CALL"
        )

        private data class ConnectProfiledCall(val profiledCall: ProfiledCall, val connectStartTimestamp: Long)

        @JvmStatic
        fun newDecorator(profiler: Profiler): Function<HttpClient, HttpClient> =
            Function {
                ProfiledHttpClient(it, profiler)
            }

    }

}


