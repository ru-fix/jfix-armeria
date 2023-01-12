package ru.fix.armeria.micrometer.tags.customizer

import com.linecorp.armeria.common.logging.RequestOnlyLog
import com.linecorp.armeria.common.metric.MeterIdPrefix
import com.linecorp.armeria.common.metric.MeterIdPrefixFunctionCustomizer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import ru.fix.armeria.micrometer.tags.MetricTags

fun interface PathMetricTagValueCustomizer {
    operator fun invoke(decodedPath: String): String

    companion object {
        val IDENTITY = PathMetricTagValueCustomizer { it }
    }
}

internal class HttpRequestPathTagCustomizer(
    private val customizeTagValue: PathMetricTagValueCustomizer = PathMetricTagValueCustomizer.IDENTITY
) : MeterIdPrefixFunctionCustomizer {
    override fun apply(
        registry: MeterRegistry,
        log: RequestOnlyLog,
        meterIdPrefix: MeterIdPrefix
    ): MeterIdPrefix = meterIdPrefix.withTags(
        listOf(
            Tag.of(
                MetricTags.PATH,
                customizeTagValue(
                    decodedPath = log.context().decodedPath()
                )
            )
        )
    )
}