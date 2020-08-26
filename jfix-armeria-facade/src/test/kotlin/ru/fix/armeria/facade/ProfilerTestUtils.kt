package ru.fix.armeria.facade

import ru.fix.aggregating.profiler.ProfiledCallReport
import ru.fix.aggregating.profiler.ProfilerReport

object ProfilerTestUtils {

    fun ProfilerReport.indicatorWithNameEnding(nameEnding: String): Long? =
        indicators.mapKeys { it.key.name }.toList().singleOrNull { (name, _) ->
            name.endsWith(nameEnding)
        }?.second

    fun ProfilerReport.profiledCallReportWithName(name: String): ProfiledCallReport? =
        profilerCallReports.singleOrNull { it.identity.name == name }

}