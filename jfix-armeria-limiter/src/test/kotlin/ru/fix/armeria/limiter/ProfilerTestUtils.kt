package ru.fix.armeria.limiter

import ru.fix.aggregating.profiler.ProfiledCallReport
import ru.fix.aggregating.profiler.ProfilerReport

object ProfilerTestUtils {

    fun ProfilerReport.indicatorWithNameEnding(nameEnding: String): Long? =
        indicators.mapKeys { it.key.name }.toList().singleOrNull { (name, _) ->
            name.endsWith(nameEnding)
        }?.second

    fun ProfilerReport.profiledCallReportWithNameEnding(nameEnding: String): ProfiledCallReport? =
        profilerCallReports.singleOrNull { it.identity.name.endsWith(nameEnding) }

    fun ProfilerReport.profiledCallReportsWithNameEnding(nameEnding: String): List<ProfiledCallReport> =
        profilerCallReports.filter { it.identity.name.endsWith(nameEnding) }

}