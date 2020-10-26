package ru.fix.armeria.facade.it

import ru.fix.aggregating.profiler.AggregatingProfiler
import ru.fix.aggregating.profiler.Profiler
import ru.fix.aggregating.profiler.ProfilerReporter

data class JFixArmeriaClientPerformanceTestCaseCreator(
    val testCaseName: String,
    private val clientName: String,
    private val expectedMetricSuffix: String? = null,
    private val testClientCreator: (Input) -> Pair<AutoCloseable, TestApi>
) : () -> JFixArmeriaClientPerformanceTestCaseCreator.Data {

    data class Input(
        val clientName: String,
        val profiler: Profiler
    )

    data class Data(
        val clientName: String,
        val expectedMetricSuffix: String?,
        val reporter: ProfilerReporter,
        val testApi: TestApi,
        val autoCloseableResource: AutoCloseable
    )

    override fun invoke(): Data {
        val profiler = AggregatingProfiler()
        val reporter = profiler.createReporter()
        val (autoCloseable, testApi) = testClientCreator(Input(clientName, profiler))
        return Data(clientName, expectedMetricSuffix, reporter, testApi, autoCloseable)
    }
}