package ru.fix.armeria.test.webflux.server.web

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.apache.logging.log4j.kotlin.Logging
import org.springframework.http.MediaType
import org.springframework.util.MimeType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ThreadLocalRandom

@RequestMapping(Api.V1)
@RestController
class DelayedAnswersRest {

    @GetMapping("/delayedAnswer")
    suspend fun delayedAnswer(
        @RequestParam delayMs: Long,
        @RequestParam jitter: Long?
    ): String {
        val finalDelayMs = jitter?.let {
            delayMs + ThreadLocalRandom.current().nextLong(-jitter, jitter)
        } ?: delayMs
        delay(finalDelayMs)
        return "Response delayed for $finalDelayMs ms"
    }

    @GetMapping("/delayedParts", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun delayedParts(
        @RequestParam partsCount: Int,
        @RequestParam partSize: Int,
        @RequestParam(required = false, defaultValue = "false") delayFirstPart: Boolean,
        @RequestParam(required = false, defaultValue = "1000") delayBetweenPartsMs: Long
    ): Flow<ByteArray> {
        val part = ByteArray(size = partSize) {
            1
        }
        return flow {
            if (delayFirstPart) {
                for (partIndex in 1..partsCount) {
                    delay(delayBetweenPartsMs)
                    emit(part)
                }
            } else {
                emit(part)
                for (partIndex in 1 until partsCount) {
                    delay(delayBetweenPartsMs)
                    emit(part)
                }
            }
        }
    }


    companion object : Logging
}