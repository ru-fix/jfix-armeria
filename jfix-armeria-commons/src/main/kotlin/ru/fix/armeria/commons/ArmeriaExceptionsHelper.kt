package ru.fix.armeria.commons

import com.linecorp.armeria.client.UnprocessedRequestException
import com.linecorp.armeria.common.util.Exceptions
import java.io.IOException

fun Throwable.unwrapUnprocessedExceptionIfNecessary(): Throwable = when (this) {
    is UnprocessedRequestException -> this.cause
    else -> this
}

private fun Throwable.unwrapIOExceptionIfNecessary(): Throwable = when (this) {
    is IOException -> this.cause!!
    else -> this
}

fun Throwable.getActualResponseExceptionCause(): Throwable =
    Exceptions.peel(this).unwrapIOExceptionIfNecessary()

