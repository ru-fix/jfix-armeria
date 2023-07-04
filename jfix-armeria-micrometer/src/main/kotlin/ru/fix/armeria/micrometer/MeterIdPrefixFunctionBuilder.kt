package ru.fix.armeria.micrometer

import com.linecorp.armeria.common.metric.MeterIdPrefixFunction
import com.linecorp.armeria.common.metric.MeterIdPrefixFunctionCustomizer
import io.micrometer.core.instrument.MeterRegistry
import ru.fix.armeria.micrometer.tags.customizer.HttpClientNameTagCustomizer
import ru.fix.armeria.micrometer.tags.customizer.PathMetricTagValueCustomizer

class MeterIdPrefixFunctionBuilder private constructor(
    private val meterRegistry: MeterRegistry,
    private val metricsNamesPrefix: String,
    private var httpClientNameTagValue: String?,
    private val baseMeterIdPrefixFunctionCreator: (metricsNamesPrefix: String) -> MeterIdPrefixFunction
) {

    companion object {

        private val DEFAULT_METER_ID_PREFIX_FUNCTION_CREATOR: (metricsNamesPrefix: String) -> MeterIdPrefixFunction =
            MeterIdPrefixFunction::ofDefault

        @JvmStatic
        @JvmOverloads
        @Deprecated(
            message = "It is better to specify client name in separate metric tag.",
            replaceWith = ReplaceWith(
                """createWithClientNameTag(meterRegistry, "specify_client_name_here")"""
            )
        )
        fun create(
            meterRegistry: MeterRegistry,
            metricsNamesPrefix: String = "",
            baseMeterIdPrefixFunctionCreator: (metricsNamesPrefix: String) -> MeterIdPrefixFunction =
                DEFAULT_METER_ID_PREFIX_FUNCTION_CREATOR
        ): MeterIdPrefixFunctionBuilder = MeterIdPrefixFunctionBuilder(
            meterRegistry = meterRegistry,
            metricsNamesPrefix = metricsNamesPrefix,
            httpClientNameTagValue = null,
            baseMeterIdPrefixFunctionCreator = baseMeterIdPrefixFunctionCreator
        )

        @JvmStatic
        @JvmOverloads
        fun createWithClientNameTag(
            meterRegistry: MeterRegistry,
            httpClientName: String,
            metricsNamesPrefix: String = "http_client",
            baseMeterIdPrefixFunctionCreator: (metricsNamesPrefix: String) -> MeterIdPrefixFunction =
                DEFAULT_METER_ID_PREFIX_FUNCTION_CREATOR
        ): MeterIdPrefixFunctionBuilder = MeterIdPrefixFunctionBuilder(
            meterRegistry = meterRegistry,
            metricsNamesPrefix = metricsNamesPrefix,
            httpClientNameTagValue = httpClientName,
            baseMeterIdPrefixFunctionCreator = baseMeterIdPrefixFunctionCreator
        )
    }

    private var httpClientNameTagLimitConfig: MaxMetricTagValuesLimitConfig =
        DefaultTagsRestrictions.LimitConfigs.HTTP_CLIENT_NAME

    private var pathTagEnabled: Boolean = false
    private var pathTagLimitConfig: MaxMetricTagValuesLimitConfig =
        DefaultTagsRestrictions.LimitConfigs.PATH
    private var pathTagValueCustomizer: PathMetricTagValueCustomizer = PathMetricTagValueCustomizer.IDENTITY

    private var remoteAddressTagEnabled: Boolean = false
    private var remoteAddressTagLimitConfig: MaxMetricTagValuesLimitConfig =
        DefaultTagsRestrictions.LimitConfigs.REMOTE_ADDRESS

    private var remoteHostTagEnabled: Boolean = false
    private var remoteHostTagLimitConfig: MaxMetricTagValuesLimitConfig =
        DefaultTagsRestrictions.LimitConfigs.REMOTE_HOST

    private var remotePortTagEnabled: Boolean = false
    private var remotePortTagLimitConfig: MaxMetricTagValuesLimitConfig =
        DefaultTagsRestrictions.LimitConfigs.REMOTE_PORT

    fun withHttpClientNameTag(
        httpClientName: String,
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.HTTP_CLIENT_NAME,
    ): MeterIdPrefixFunctionBuilder = apply {
        this.httpClientNameTagValue = httpClientName
        httpClientNameTagLimitConfig = limitConfig
    }

    fun withHttpClientNameTagLimitConfig(
        limitConfig: MaxMetricTagValuesLimitConfig,
    ): MeterIdPrefixFunctionBuilder {
        val tagValue = httpClientNameTagValue
        requireNotNull(tagValue) { "http client name tag value is not specified" }
        return withHttpClientNameTag(
            httpClientName = tagValue,
            limitConfig = limitConfig
        )
    }

    fun withPathTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.PATH,
        tagValueCustomizer: PathMetricTagValueCustomizer = PathMetricTagValueCustomizer.IDENTITY
    ): MeterIdPrefixFunctionBuilder = apply {
        pathTagEnabled = true
        pathTagLimitConfig = limitConfig
        pathTagValueCustomizer = tagValueCustomizer
    }

    fun withRemoteAddressTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_ADDRESS
    ): MeterIdPrefixFunctionBuilder = apply {
        remoteAddressTagEnabled = true
        remoteAddressTagLimitConfig = limitConfig
    }

    fun withRemoteHostTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_HOST
    ): MeterIdPrefixFunctionBuilder = apply {
        remoteHostTagEnabled = true
        remoteHostTagLimitConfig = limitConfig
    }

    fun withRemotePortTag(
        limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_PORT
    ): MeterIdPrefixFunctionBuilder = apply {
        remotePortTagEnabled = true
        remotePortTagLimitConfig = limitConfig
    }

    fun build(): MeterIdPrefixFunction {
        return baseMeterIdPrefixFunctionCreator(metricsNamesPrefix)
            .enrichWithTagIfEnabled(
                httpClientNameTagValue != null,
                httpClientNameTagLimitConfig,
                MeterIdPrefixFunctionCustomizers::restrictMaxAmountOfHttpClientNameTagValues
            ) {
                HttpClientNameTagCustomizer(httpClientNameTagValue!!)
            }
            .enrichWithTagIfEnabled(
                pathTagEnabled,
                pathTagLimitConfig,
                MeterIdPrefixFunctionCustomizers.HttpRequestPath::restrictMaxAmountOfPathTagValues
            ) {
                MeterIdPrefixFunctionCustomizers.HttpRequestPath.customizer(pathTagValueCustomizer)
            }
            .enrichWithTagIfEnabled(
                remoteAddressTagEnabled,
                remoteAddressTagLimitConfig,
                MeterIdPrefixFunctionCustomizers.RemoteAddressInfo::restrictMaxAmountOfRemoteAddressTagValues,
                MeterIdPrefixFunctionCustomizers.RemoteAddressInfo::remoteAddressCustomizer
            ).enrichWithTagIfEnabled(
                remoteHostTagEnabled,
                remoteHostTagLimitConfig,
                MeterIdPrefixFunctionCustomizers.RemoteAddressInfo::restrictMaxAmountOfRemoteHostTagValues,
                MeterIdPrefixFunctionCustomizers.RemoteAddressInfo::remoteHostCustomizer
            ).enrichWithTagIfEnabled(
                remotePortTagEnabled,
                remotePortTagLimitConfig,
                MeterIdPrefixFunctionCustomizers.RemoteAddressInfo::restrictMaxAmountOfRemotePortTagValues,
                MeterIdPrefixFunctionCustomizers.RemoteAddressInfo::remotePortCustomizer
            )
    }

    private fun MeterIdPrefixFunction.enrichWithTagIfEnabled(
        tagEnabled: Boolean,
        limitConfig: MaxMetricTagValuesLimitConfig,
        limitUniqueTagValuesCount: (MeterRegistry, String, MaxMetricTagValuesLimitConfig) -> Unit,
        createTagCustomizer: () -> MeterIdPrefixFunctionCustomizer
    ): MeterIdPrefixFunction {
        if (tagEnabled) {
            limitUniqueTagValuesCount(meterRegistry, metricsNamesPrefix, limitConfig)

            return this.andThen(createTagCustomizer())
        }

        return this
    }

}