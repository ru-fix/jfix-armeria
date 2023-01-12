package ru.fix.armeria.micrometer

internal object DefaultTagsRestrictions {

    object MaxCountOfUniqueValues {
        const val PATH: Byte = 10
        const val REMOTE_ADDRESS: Byte = 10
        const val REMOTE_HOST: Byte = 10
        const val REMOTE_PORT: Byte = 3
    }

    object LimitConfigs {
        val PATH = MaxMetricTagValuesLimitConfig(
            maxCountOfUniqueTagValues = MaxCountOfUniqueValues.PATH
        )
        val REMOTE_ADDRESS = MaxMetricTagValuesLimitConfig(
            maxCountOfUniqueTagValues = MaxCountOfUniqueValues.REMOTE_ADDRESS
        )
        val REMOTE_HOST = MaxMetricTagValuesLimitConfig(
            maxCountOfUniqueTagValues = DefaultTagsRestrictions.MaxCountOfUniqueValues.REMOTE_HOST
        )
        val REMOTE_PORT = MaxMetricTagValuesLimitConfig(
            maxCountOfUniqueTagValues = DefaultTagsRestrictions.MaxCountOfUniqueValues.REMOTE_PORT
        )
    }

}