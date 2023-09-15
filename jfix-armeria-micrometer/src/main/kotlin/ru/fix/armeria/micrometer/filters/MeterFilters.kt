package ru.fix.armeria.micrometer.filters

import io.micrometer.core.instrument.config.MeterFilter

fun MeterFilter.applyForMetricsWithClientNameTag(
    httpClientNameTagValue: String,
): MeterFilter = ByHttpClientTagValueMeterFilter(httpClientNameTagValue, this)
