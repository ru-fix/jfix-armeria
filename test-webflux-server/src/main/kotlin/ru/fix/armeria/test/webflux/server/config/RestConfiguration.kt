package ru.fix.armeria.test.webflux.server.config

import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import ru.fix.armeria.test.webflux.server.web.DelayedAnswersRest

@Configuration
@Import(
    ReactiveWebServerFactoryAutoConfiguration::class,
    HttpHandlerAutoConfiguration::class,
    WebFluxAutoConfiguration::class
)
open class RestConfiguration {

    @Bean
    open fun delayedAnswerRest(): DelayedAnswersRest = DelayedAnswersRest()
}