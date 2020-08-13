package ru.fix.armeria.commons

import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.common.HttpStatus
import java.net.ConnectException

val On503AndConnectExceptionRetryRule: RetryRule = RetryRule.onStatus(HttpStatus.SERVICE_UNAVAILABLE)
    .orElse(RetryRule.onException { _, thr ->
        thr.unwrapUnprocessedExceptionIfNecessary() is ConnectException
    })
