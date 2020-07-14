import com.linecorp.armeria.common.HttpMethod

internal typealias MetricTag = Pair<String, String>

internal object Metrics {
    const val ACTIVE_REQUESTS = "active"
    const val PENDING_REQUESTS = "pending"
    const val WAIT_LATENCY = "wait"
    const val WAIT_TIMEOUT = "timeout"
}

internal object MetricTags {
    const val PATH = "path"
    const val METHOD = "method"

    fun build(
        path: String,
        method: HttpMethod
    ): Map<String, String> = mapOf<String, String>(
        PATH to path,
        METHOD to method.name
    )
}