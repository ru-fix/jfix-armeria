package ru.fix.armeria.micrometer.tags.customizer

import com.linecorp.armeria.common.logging.RequestOnlyLog
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.metric.MeterIdPrefixFunctionCustomizer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import ru.fix.armeria.commons.endpointInetSockedAddress
import ru.fix.armeria.micrometer.tags.MetricTags

object RemotePortTagCustomizer : MeterIdPrefixFunctionCustomizer {
    override fun apply(
        registry: MeterRegistry,
        log: RequestOnlyLog,
        meterIdPrefix: MeterIdPrefix
    ): MeterIdPrefix {
        val port = log.context()
            .endpointInetSockedAddress
            ?.port
            ?: return meterIdPrefix

        return meterIdPrefix.withTags(
            listOf(
                Tag.of(MetricTags.REMOTE_PORT, port.toString())
            )
        )
    }
}