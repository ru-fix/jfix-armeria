package ru.fix.armeria.facade

import ru.fix.armeria.facade.webclient.PreparingHttpClientBuilder
import ru.fix.armeria.facade.webclient.impl.PreparingHttpClientBuilderImpl

object HttpClients {

    @JvmStatic
    fun builder(): PreparingHttpClientBuilder = PreparingHttpClientBuilderImpl()

}