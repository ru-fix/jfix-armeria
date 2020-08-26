package ru.fix.armeria.facade.webclient

import com.linecorp.armeria.client.WebClient

class CloseableWebClient internal constructor(
    delegateWebClient: WebClient,
    delegateCloseable: AutoCloseable
): WebClient by delegateWebClient, AutoCloseable by delegateCloseable

