package ru.fix.armeria.test.webflux.server

import org.springframework.boot.runApplication
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import ru.fix.armeria.test.webflux.server.config.RestConfiguration

@Configuration
@Import(RestConfiguration::class)
open class TestWebfluxApplication

fun main(args: Array<String>) {
    runApplication<TestWebfluxApplication>(*args)
}