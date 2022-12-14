package ru.fix.armeria.micrometer.filters

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import org.apache.logging.log4j.kotlin.logger
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.logging.log4j.Level as Log4jLevel

/**
 * Extended version of [io.micrometer.core.instrument.internal.OnlyOnceLoggingDenyMeterFilter] with additional
 * configuration options.
 *
 * @param logLevel level of logged message
 * @param messageSupplier log message creating function
 */
class OnlyOnceLoggingDenyMeterFilter(
    private val logLevel: LogLevel = LogLevel.ERROR,
    private val messageSupplier: (Meter.Id) -> String
) : MeterFilter {

    private val alreadyLogged = AtomicBoolean(false)

    override fun accept(id: Meter.Id): MeterFilterReply {
        val log4j2Level = logLevel.log4jLevel
        if (logger.delegate.isEnabled(log4j2Level) && alreadyLogged.compareAndSet(false, true)) {
            logger.log(log4j2Level) {
                messageSupplier(id)
            }
        }
        return MeterFilterReply.DENY
    }

    enum class LogLevel(
        internal val log4jLevel: Log4jLevel
    ) {
        WARN(Log4jLevel.WARN), ERROR(Log4jLevel.ERROR);

    }

    companion object {
        private val logger = logger()
    }

}