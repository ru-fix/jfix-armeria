package ru.fix.armeria.commons

import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.common.HttpStatus

val On503AndUnprocessedRetryRule: RetryRule = RetryRule
    .builder()
    .onStatus(HttpStatus.SERVICE_UNAVAILABLE)
    .onUnprocessed()
    .thenBackoff()
