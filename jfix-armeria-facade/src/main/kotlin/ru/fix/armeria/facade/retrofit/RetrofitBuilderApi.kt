package ru.fix.armeria.facade.retrofit

import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofitBuilder
import retrofit2.Converter
import ru.fix.aggregating.profiler.Profiler
import ru.fix.dynamic.property.api.DynamicProperty
import java.time.Duration
import java.util.concurrent.ExecutorService

interface RetrofitHttpClientBuilder {

    fun addConverterFactory(factory: Converter.Factory): RetrofitHttpClientBuilder


    /**
     * [jfix-stdlib-concurrency](https://github.com/ru-fix/jfix-stdlib) dependency required.
     */
    fun enableNamedBlockingResponseReadingExecutor(
        maxPoolSizeProp: DynamicProperty<Int>,
        profiler: Profiler,
        shutdownTimeoutProp: DynamicProperty<Duration>
    ): RetrofitHttpClientBuilder

    /**
     * [jfix-stdlib-concurrency](https://github.com/ru-fix/jfix-stdlib) dependency required.
     */
    fun setBlockingResponseReadingExecutor(
        retrofitCallbackExecutor: ExecutorService,
        shutdownTimeout: Duration
    ): RetrofitHttpClientBuilder

    /**
     * [jfix-stdlib-concurrency](https://github.com/ru-fix/jfix-stdlib) dependency required.
     */
    fun setBlockingResponseReadingExecutor(
        retrofitCallbackExecutor: ExecutorService,
        shutdownTimeoutProp: DynamicProperty<Duration>
    ): RetrofitHttpClientBuilder


    fun buildRetrofit(): CloseableRetrofit

    /*
     Low-level Armeria configuration methods.
     BE AWARE that they always applied at the end of building and can override some options,
     set by other builder's methods.
     */

    fun customizeArmeriaRetrofitBuilder(
        customizer: ArmeriaRetrofitBuilder.() -> ArmeriaRetrofitBuilder
    ): RetrofitHttpClientBuilder
}