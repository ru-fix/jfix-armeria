package ru.fix.armeria.commons

import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse

private class DecoratingHttpClient(
    delegate: Client<HttpRequest, HttpResponse>
) : HttpClient, Client<HttpRequest, HttpResponse> by delegate

fun Client<HttpRequest, HttpResponse>.asHttpClient(): HttpClient = DecoratingHttpClient(this)