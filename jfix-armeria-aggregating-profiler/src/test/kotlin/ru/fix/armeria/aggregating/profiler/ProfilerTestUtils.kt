package ru.fix.armeria.aggregating.profiler

import ru.fix.aggregating.profiler.ProfiledCallReport
import ru.fix.aggregating.profiler.ProfilerReport

object ProfilerTestUtils {

    const val EPOLL_SOCKET_CHANNEL = "EpollSocketChannel"

    fun ProfilerReport.indicatorWithNameEnding(nameEnding: String): Long? =
        indicators.mapKeys { it.key.name }.toList().singleOrNull { (name, _) ->
            name.endsWith(nameEnding)
        }?.second

    fun ProfilerReport.profiledCallReportWithNameEnding(nameEnding: String): ProfiledCallReport? =
        profilerCallReports.singleOrNull { it.identity.name.endsWith(nameEnding) }

    fun ProfilerReport.profiledCallReportsWithNameEnding(nameEnding: String): List<ProfiledCallReport> =
        profilerCallReports.filter { it.identity.name.endsWith(nameEnding) }

}