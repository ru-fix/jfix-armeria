package ru.fix.armeria.micrometer

import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import io.micrometer.core.instrument.MeterRegistry
import ru.fix.armeria.micrometer.tags.customizer.PathMetricTagValueCustomizer

class MeterIdPrefixFunctionBuilder(
    private val meterRegistry: MeterRegistry,
    private val metricsNamesPrefix: String = "",
    private val baseMeterIdPrefixFunctionCreator: (metricNamePrefix: String) -> MeterIdPrefixFunction = {
        MeterIdPrefixFunction.ofDefault(metricsNamesPrefix)
    }
) {

    fun withPathTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.PATH,
        tagValueCustomizer: PathMetricTagValueCustomizer = PathMetricTagValueCustomizer.IDENTITY
    ): MeterIdPrefixFunctionBuilder = TODO()

    fun withRemoteAddressTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_ADDRESS
    ): MeterIdPrefixFunctionBuilder = TODO()

    fun withRemoteHostTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_HOST
    ): MeterIdPrefixFunctionBuilder = TODO()

    fun withRemotePortTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_PORT
    ): MeterIdPrefixFunctionBuilder = TODO()

    fun build(): MeterIdPrefixFunction = TODO()

}