package ru.fix.armeria.aggregating.profiler

import com.linecorp.armeria.client.ConnectionPoolListener
import com.linecorp.armeria.client.ConnectionPoolListenerWrapper
import com.linecorp.armeria.common.SessionProtocol
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import io.netty.util.AttributeMap
import ru.fix.aggregating.profiler.Identity
import ru.fix.aggregating.profiler.ProfiledCall
import ru.fix.aggregating.profiler.Profiler
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class ProfiledConnectionPoolListener(
    private val profiler: Profiler,
    delegate: ConnectionPoolListener
) : ConnectionPoolListenerWrapper(delegate) {

    companion object {
        private val CONNECTION_LIFETIME_PROFILED_CALL =
            AttributeKey.valueOf<ProfiledCall>(
                ProfiledConnectionPoolListener::class.java,
                "CONNECTION_LIFETIME_PROFILED_CALL"
            )
    }

    private val activeChannelsCount = AtomicInteger()

    init {
        profiler.attachIndicator(Metrics.ACTIVE_CHANNELS_COUNT) {
            activeChannelsCount.toLong()
        }
    }

    override fun connectionOpen(
        protocol: SessionProtocol,
        remoteAddr: InetSocketAddress,
        localAddr: InetSocketAddress,
        attrs: AttributeMap
    ) {
        activeChannelsCount.incrementAndGet()

        val profiledCall = profiler.profiledCall(
            Identity(
                Metrics.CONNECTION_LIFETIME,
                MetricTags.build(
                    remoteInetSocketAddress = remoteAddr,
                    protocol = protocol,
                    channel = attrs as? Channel
                )
            ))
        attrs.attr(CONNECTION_LIFETIME_PROFILED_CALL).set(profiledCall)
        profiledCall.start()

        super.connectionOpen(protocol, remoteAddr, localAddr, attrs)
    }

    override fun connectionClosed(
        protocol: SessionProtocol,
        remoteAddr: InetSocketAddress,
        localAddr: InetSocketAddress,
        attrs: AttributeMap
    ) {
        activeChannelsCount.decrementAndGet()

        val profiledCall = attrs.attr(CONNECTION_LIFETIME_PROFILED_CALL).getAndSet(null)
        profiledCall?.stop()

        super.connectionClosed(protocol, remoteAddr, localAddr, attrs)
    }
}