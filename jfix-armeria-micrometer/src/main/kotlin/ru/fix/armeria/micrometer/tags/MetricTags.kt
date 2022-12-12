package ru.fix.armeria.micrometer.tags

internal object MetricTags {
    const val PATH = "path"
    const val REMOTE_ADDRESS = "remote_address"
    const val REMOTE_HOST = "remote_host"
    const val REMOTE_PORT = "remote_port"

    const val TOO_MANY_TAG_VALUES_REPLACEMENT_VALUE = "too_many_values"
}