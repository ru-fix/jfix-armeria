package ru.fix.armeria.commons

import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.common.logging.RequestLog
import com.linecorp.armeria.common.logging.RequestLogProperty
import com.linecorp.armeria.common.logging.RequestOnlyLog
import java.net.InetSocketAddress
import java.net.SocketAddress

val RequestOnlyLog.sessionProtocol: SessionProtocol?
    get() = if (isAvailable(RequestLogProperty.SESSION)) {
        sessionProtocol()
    } else {
        null
    }

val RequestContext.endpointInetSockedAddress: InetSocketAddress?
    get() = remoteAddress<SocketAddress>()?.let {
        when (it) {
            is InetSocketAddress -> it
            else -> null
        }
    }

val RequestLog.knownResponseStatus: HttpStatus?
    get() = when {
        !isAvailable(RequestLogProperty.RESPONSE_HEADERS) -> null
        responseHeaders().status() == HttpStatus.UNKNOWN -> null
        else -> responseHeaders().status()
    }

