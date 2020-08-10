package ru.fix.armeria.commons

import com.linecorp.armeria.client.Client
import com.linecorp.armeria.client.HttpClient
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.Request
import com.linecorp.armeria.common.Response

interface AutoCloseableClient<RequestT : Request, ResponseT : Response> :
    Client<RequestT, ResponseT>,
    AutoCloseable

class AutoCloseableHttpClient<DelegateT>(
    private val delegate: DelegateT
) : HttpClient,
    AutoCloseable by delegate,
    Client<HttpRequest, HttpResponse> by delegate,
    AutoCloseableClient<HttpRequest, HttpResponse>
        where DelegateT : AutoCloseable, DelegateT : Client<HttpRequest, HttpResponse> {

    override fun toString(): String {
        return delegate.toString()
    }
}