package ru.fix.armeria.micrometer

import io.micrometer.core.instrument.config.MeterFilter
import ru.fix.armeria.micrometer.MaxMetricTagValuesLimitConfig.OnMaxReachedMeterFilterProvider
import ru.fix.armeria.micrometer.tags.MetricTags

data class MaxMetricTagValuesLimitConfig(
    val maxCountOfUniqueTagValues: Byte,
    val getOnMaxReachedMeterFilter: OnMaxReachedMeterFilterProvider = OnMaxReachedMeterFilterProvider {
        MeterFilter.replaceTagValues(
            it,
            { MetricTags.TOO_MANY_TAG_VALUES_REPLACEMENT_VALUE }
        )
    }
) {

    fun interface OnMaxReachedMeterFilterProvider {

        operator fun invoke(metricName: String): MeterFilter
    }

}