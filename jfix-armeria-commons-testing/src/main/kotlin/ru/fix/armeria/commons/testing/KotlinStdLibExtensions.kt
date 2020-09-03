package ru.fix.armeria.commons.testing

import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration

@ExperimentalTime
val Duration.j: java.time.Duration
    get() = toJavaDuration()