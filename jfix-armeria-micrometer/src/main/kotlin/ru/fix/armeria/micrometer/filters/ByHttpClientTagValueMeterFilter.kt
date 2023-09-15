package ru.fix.armeria.micrometer.filters

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import ru.fix.armeria.micrometer.tags.MetricTags

class ByHttpClientTagValueMeterFilter(
    private val httpClientNameTagValue: String,
    private val filterToApplyForSuchMetrics: MeterFilter,
) : MeterFilter {

    override fun map(id: Meter.Id): Meter.Id {
        if (httpClientNameTagExistsAndIsExpected(id)) {
            return filterToApplyForSuchMetrics.map(id)
        }
        return super.map(id)
    }

    override fun accept(id: Meter.Id): MeterFilterReply {
        if (httpClientNameTagExistsAndIsExpected(id)) {
            return filterToApplyForSuchMetrics.accept(id)
        }
        return super.accept(id)
    }

    override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig? {
        if (httpClientNameTagExistsAndIsExpected(id)) {
            return filterToApplyForSuchMetrics.configure(id, config)
        }
        return super.configure(id, config)
    }

    private fun httpClientNameTagExistsAndIsExpected(id: Meter.Id) =
        id.getTag(MetricTags.HTTP_CLIENT_NAME)?.let {
            it == httpClientNameTagValue
        } ?: false
}