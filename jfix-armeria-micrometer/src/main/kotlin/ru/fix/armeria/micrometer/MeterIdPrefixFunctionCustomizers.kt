package ru.fix.armeria.micrometer

import com.linecorp.armeria.common.metric.MeterIdPrefixFunctionCustomizer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.apache.logging.log4j.kotlin.logger
import ru.fix.armeria.micrometer.tags.MetricTags
import ru.fix.armeria.micrometer.tags.customizer.*

/**
 * Additional [MeterIdPrefixFunctionCustomizer]s for Micrometer-Armeria integration.
 * All additionally configurable tags are listed in [MetricTags].
 */
object MeterIdPrefixFunctionCustomizers {

    /**
     * Http request [MetricTags.PATH] tag related functions.
     */
    object HttpRequestPath {

        /**
         * Configure upper bound restriction on max count of unique values of [MetricTags.PATH] tag.
         */
        @JvmStatic
        @JvmOverloads
        fun restrictMaxAmountOfPathTagValues(
            meterRegistry: MeterRegistry,
            metricNamePrefix: String = "",
            limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.PATH
        ): Unit = restrictMaxAmountOfTagValues(
            meterRegistry,
            metricNamePrefix,
            metricTagName = MetricTags.PATH,
            limitConfig
        )

        @JvmStatic
        fun customizer(
            pathTagValueCustomizer: PathMetricTagValueCustomizer = PathMetricTagValueCustomizer.IDENTITY
        ): MeterIdPrefixFunctionCustomizer = HttpRequestPathTagCustomizer(pathTagValueCustomizer)
    }

    /**
     * Remote endpoint address info tags' related functions. Covered tag keya are:
     * - [MetricTags.REMOTE_ADDRESS]
     * - [MetricTags.REMOTE_HOST]
     * - [MetricTags.REMOTE_PORT]
     */
    object RemoteAddressInfo {

        @JvmStatic
        @JvmOverloads
        fun restrictMaxAmountOfRemoteAddressTagValues(
            meterRegistry: MeterRegistry,
            metricNamePrefix: String = "",
            limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_ADDRESS,
        ) {
            restrictMaxAmountOfTagValues(
                meterRegistry,
                metricNamePrefix,
                metricTagName = MetricTags.REMOTE_ADDRESS,
                limitConfig
            )
        }

        @JvmStatic
        @JvmOverloads
        fun restrictMaxAmountOfRemoteHostTagValues(
            meterRegistry: MeterRegistry,
            metricNamePrefix: String = "",
            limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_HOST,
        ) {
            restrictMaxAmountOfTagValues(
                meterRegistry,
                metricNamePrefix,
                metricTagName = MetricTags.REMOTE_HOST,
                limitConfig
            )
        }

        @JvmStatic
        @JvmOverloads
        fun restrictMaxAmountOfRemotePortTagValues(
            meterRegistry: MeterRegistry,
            metricNamePrefix: String = "",
            limitConfig: MaxMetricTagValuesLimitConfig = DefaultTagsRestrictions.LimitConfigs.REMOTE_PORT,
        ) {
            restrictMaxAmountOfTagValues(
                meterRegistry,
                metricNamePrefix,
                metricTagName = MetricTags.REMOTE_PORT,
                limitConfig
            )
        }

        /**
         * Configure per tag upper bound restriction on max count of unique values of remote address info tags.
         */
        @JvmStatic
        @JvmOverloads
        fun restrictMaxAmountOfAddressInfoTagsValues(
            meterRegistry: MeterRegistry,
            metricNamePrefix: String = "",
            remoteAddressLimitConfig: MaxMetricTagValuesLimitConfig =
                DefaultTagsRestrictions.LimitConfigs.REMOTE_ADDRESS,
            remoteHostLimitConfig: MaxMetricTagValuesLimitConfig =
                DefaultTagsRestrictions.LimitConfigs.REMOTE_HOST,
            remotePortLimitConfig: MaxMetricTagValuesLimitConfig =
                DefaultTagsRestrictions.LimitConfigs.REMOTE_PORT
        ) {
            restrictMaxAmountOfRemoteAddressTagValues(
                meterRegistry,
                metricNamePrefix,
                remoteAddressLimitConfig
            )
            restrictMaxAmountOfRemoteHostTagValues(
                meterRegistry,
                metricNamePrefix,
                remoteHostLimitConfig
            )
            restrictMaxAmountOfRemotePortTagValues(
                meterRegistry,
                metricNamePrefix,
                remotePortLimitConfig
            )
        }

        /**
         * Creates customizer combining [remoteAddressCustomizer], [remoteHostCustomizer], [remotePortCustomizer].
         */
        @JvmStatic
        fun remoteEndpointInfoCustomizer(): MeterIdPrefixFunctionCustomizer =
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

        /**
         * Create [MeterIdPrefixFunctionCustomizer] that will add [MetricTags.REMOTE_ADDRESS] tag to meters.
         * It shows ip address of remote endpoint.
         */
        @JvmStatic
        fun remoteAddressCustomizer(): MeterIdPrefixFunctionCustomizer =
            RemoteAddressTagCustomizer

        /**
         * Create [MeterIdPrefixFunctionCustomizer] that will add [MetricTags.REMOTE_HOST] tag to meters.
         */
        @JvmStatic
        fun remoteHostCustomizer(): MeterIdPrefixFunctionCustomizer =
            RemoteHostTagCustomizer

        /**
         * Create [MeterIdPrefixFunctionCustomizer] that will add [MetricTags.REMOTE_PORT] tag to meters.
         */
        @JvmStatic
        fun remotePortCustomizer(): MeterIdPrefixFunctionCustomizer =
            RemotePortTagCustomizer

    }

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
            .meterFilter(maximumAllowableTags)
    }

}
