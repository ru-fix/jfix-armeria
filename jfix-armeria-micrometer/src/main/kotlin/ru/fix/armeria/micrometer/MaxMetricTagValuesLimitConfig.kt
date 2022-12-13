package ru.fix.armeria.micrometer

import io.micrometer.core.instrument.config.MeterFilter
import ru.fix.armeria.micrometer.MaxMetricTagValuesLimitConfig.OnMaxReachedMeterFilterProvider
import ru.fix.armeria.micrometer.filters.OnlyOnceLoggingDenyMeterFilter

data class MaxMetricTagValuesLimitConfig(
    val maxCountOfUniqueTagValues: Byte,
    val getOnMaxReachedMeterFilter: OnMaxReachedMeterFilterProvider =
        OnMaxReachedMeterFilterProvider { metricNamePrefix, metricTagKey ->
            OnlyOnceLoggingDenyMeterFilter {
                "Metrics with prefix $metricNamePrefix: " +
                        "number of unique values of tag <$metricTagKey> exceeded limit <$maxCountOfUniqueTagValues>. " +
                        "Last metric that tried to be recorded: $it"
            }
        }
) {

    fun interface OnMaxReachedMeterFilterProvider {

        operator fun invoke(metricNamePrefix: String, metricTagKey: String): MeterFilter
    }

}