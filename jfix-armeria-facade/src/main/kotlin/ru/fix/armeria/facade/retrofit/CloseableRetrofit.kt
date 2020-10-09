package ru.fix.armeria.facade.retrofit

import retrofit2.Retrofit

class CloseableRetrofit internal constructor(
    val retrofit: Retrofit,
    delegateCloseable: AutoCloseable
): AutoCloseable by delegateCloseable