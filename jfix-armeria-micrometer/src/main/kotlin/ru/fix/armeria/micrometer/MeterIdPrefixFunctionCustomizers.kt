package ru.fix.armeria.micrometer

import com.linecorp.armeria.common.metric.MeterIdPrefixFunctionCustomizer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.apache.logging.log4j.kotlin.logger
import ru.fix.armeria.micrometer.tags.MetricTags
import ru.fix.armeria.micrometer.tags.customizer.HttpRequestPathTagCustomizer
import ru.fix.armeria.micrometer.tags.customizer.RemoteAddressTagCustomizer
import ru.fix.armeria.micrometer.tags.customizer.RemoteHostTagCustomizer
import ru.fix.armeria.micrometer.tags.customizer.RemotePortTagCustomizer

object MeterIdPrefixFunctionCustomizers {

    private val logger = logger()

    private fun restrictMaxAmountOfTagValues(
        meterRegistry: MeterRegistry,
        metricNamePrefix: String,
        metricTagName: String,
        limitConfig: MaxMetricTagValuesLimitConfig
    ) {
        val onMaxReached = limitConfig.getOnMaxReachedMeterFilter.invoke(metricNamePrefix, metricTagName)
        logger.info {
            "Setting restriction to $meterRegistry on meters prefixed by '$metricNamePrefix'. " +
                    "Max allowed number of '$metricTagName' tag values is ${limitConfig.maxCountOfUniqueTagValues}. " +
                    "onMaxReached = $onMaxReached"
        }
        val maximumAllowableTags = MeterFilter.maximumAllowableTags(
            metricNamePrefix,
            metricTagName,
            limitConfig.maxCountOfUniqueTagValues.toInt(),
            onMaxReached
        )
        meterRegistry.config()
            .meterFilter(
                maximumAllowableTags
            )
    }

    object HttpRequestPath {

        @JvmStatic
        @JvmOverloads
        fun restrictMaxAmountOfPathTagValues(
            meterRegistry: MeterRegistry,
            metricNamePrefix: String = "",
            limitConfig: MaxMetricTagValuesLimitConfig = MaxMetricTagValuesLimitConfig(
                maxCountOfUniqueTagValues = 10
            )
        ): Unit = restrictMaxAmountOfTagValues(
            meterRegistry,
            metricNamePrefix,
            metricTagName = MetricTags.PATH,
            limitConfig
        )

        @JvmStatic
        fun customizer(): MeterIdPrefixFunctionCustomizer =
            HttpRequestPathTagCustomizer
    }

    object RemoteAddressInfo {

        @JvmStatic
        @JvmOverloads
        fun restrictMaxAmountOfAddressInfoTagsValues(
            meterRegistry: MeterRegistry,
            metricNamePrefix: String = "",
            remoteAddressLimitConfig: MaxMetricTagValuesLimitConfig = MaxMetricTagValuesLimitConfig(
                maxCountOfUniqueTagValues = 10
            ),
            remoteHostLimitConfig: MaxMetricTagValuesLimitConfig = MaxMetricTagValuesLimitConfig(
                maxCountOfUniqueTagValues = 10
            ),
            remotePortLimitConfig: MaxMetricTagValuesLimitConfig = MaxMetricTagValuesLimitConfig(
                maxCountOfUniqueTagValues = 3
            )
        ): Unit {
            restrictMaxAmountOfTagValues(
                meterRegistry,
                metricNamePrefix,
                metricTagName = MetricTags.REMOTE_ADDRESS,
                remoteAddressLimitConfig
            )
            restrictMaxAmountOfTagValues(
                meterRegistry,
                metricNamePrefix,
                metricTagName = MetricTags.REMOTE_HOST,
                remoteHostLimitConfig
            )
            restrictMaxAmountOfTagValues(
                meterRegistry,
                metricNamePrefix,
                metricTagName = MetricTags.REMOTE_PORT,
                remotePortLimitConfig
            )
        }

        @JvmStatic
        fun remoteAddressInfoCustomizer(): MeterIdPrefixFunctionCustomizer =
            MeterIdPrefixFunctionCustomizer { registry, log, meterIdPrefix ->
                remoteAddressCustomizer()
                    .apply(
                        registry, log,
                        remoteHostCustomizer()
                            .apply(
                                registry, log,
                                remotePortCustomizer()
                                    .apply(registry, log, meterIdPrefix)
                            )
                    )
            }

        @JvmStatic
        fun remoteAddressCustomizer(): MeterIdPrefixFunctionCustomizer =
            RemoteAddressTagCustomizer

        @JvmStatic
        fun remoteHostCustomizer(): MeterIdPrefixFunctionCustomizer =
            RemoteHostTagCustomizer

        @JvmStatic
        fun remotePortCustomizer(): MeterIdPrefixFunctionCustomizer =
            RemotePortTagCustomizer

    }

}
