package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.SessionProtocol
import io.netty.channel.Channel
import java.net.InetSocketAddress

internal typealias MetricTag = Pair<String, String>

internal object Metrics {
    const val HTTP_CONNECT = "http.connect"
    const val HTTP_CONNECTED = "http.connected"
    const val HTTP_ERROR = "http.error"
    const val HTTP_SUCCESS = "http"

    const val ACTIVE_CHANNELS_COUNT = "activeChannelsCount"
    const val CONNECTION_LIFETIME = "armeria_connections"
}

internal object MetricTags {
    const val PATH = "path"
    const val METHOD = "method"
    const val REMOTE_ADDRESS = "remote_address"
    const val REMOTE_HOST = "remote_host"
    const val REMOTE_PORT = "remote_port"
    const val PROTOCOL = "proto"
    const val IS_MULTIPLEX_PROTOCOL = "is_proto_multiplex"
    const val CHANNEL_CLASS = "channel_class"

    const val ERROR_TYPE = "error_type"

    const val RESPONSE_STATUS = "status"

    fun build(
        path: String? = null,
        method: HttpMethod? = null,
        remoteInetSocketAddress: InetSocketAddress? = null,
        responseStatusCode: HttpStatus? = null,
        protocol: SessionProtocol? = null,
        channel: Channel? = null
    ): Map<String, String> = mutableMapOf<String, String>().apply {

        path
            ?.let { this += (PATH to it) }
        method
            ?.let { this += (METHOD to it.name) }
        remoteInetSocketAddress?.let {
            this += (REMOTE_ADDRESS to it.address.hostAddress)
            this += (REMOTE_HOST to it.hostName)
            this += (REMOTE_PORT to it.port.toString())
        }
        responseStatusCode
            ?.let { this += (RESPONSE_STATUS to it.codeAsText()) }
        protocol?.let {
            this += (PROTOCOL to it.uriText())
            this += (IS_MULTIPLEX_PROTOCOL to it.isMultiplex.toString())
        }
        channel
            ?.let { it::class.simpleName }
            ?.let { this += (CHANNEL_CLASS to it) }
    }
}