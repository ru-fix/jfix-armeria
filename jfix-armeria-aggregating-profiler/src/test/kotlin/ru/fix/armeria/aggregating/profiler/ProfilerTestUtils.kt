package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.testing.junit.server.ServerExtension
import java.net.URI

object ProfilerTestUtils {

    const val LOCAL_HOST = "localhost"
    const val LOCAL_ADDRESS = "127.0.0.1"
    const val EPOLL_SOCKET_CHANNEL = "EpollSocketChannel"

    private fun URI.replaceLocalAddressByHost(): URI =
        URI.create(this.toString().replace(LOCAL_ADDRESS, LOCAL_HOST))
    fun ServerExtension.localhostHttpUri(): URI = this.httpUri().replaceLocalAddressByHost()
    fun ServerExtension.localhostUri(protocol: SessionProtocol): URI =
        this.uri(protocol).replaceLocalAddressByHost()
}