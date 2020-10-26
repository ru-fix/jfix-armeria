package ru.fix.armeria.facade.it

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.MediaTypeNames
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.common.stream.NoopSubscriber
import io.netty.handler.codec.http.HttpHeaderValues
import kotlinx.coroutines.future.await
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import retrofit2.http.GET
import retrofit2.http.Query

interface TestApi {

    companion object {
        private const val BASE_PATH = "/jfix/armeria/test-webflux/v1"
        const val DELAYED_ANSWER_PATH = "$BASE_PATH/delayedAnswer"
        const val DELAYED_PARTS_PATH = "$BASE_PATH/delayedParts"
    }

    @GET(DELAYED_ANSWER_PATH)
    suspend fun delayedAnswer(
        @Query("delayMs") delayMs: Long,
        @Query("jitter") jitter: Long? = null
    ): String

    @GET(DELAYED_PARTS_PATH)
    suspend fun delayedParts(
        @Query("partsCount") partsCount: Int,
        @Query("partSize") partSizeInBytes: Int,
        @Query("delayBetweenPartsMs") delayBetweenPartsMs: Long? = null
    ): ResponseBody //force Retrofit to wait response but to do not load it into memory
}

class TestApiWebClientBasedImpl(private val webClient: WebClient) : TestApi {

    companion object {
        private val OCTET_STREAM_MEDIA_TYPE = MediaType.get(MediaTypeNames.OCTET_STREAM)
        private val EMPTY_BUFFER = Buffer()

    }

    override suspend fun delayedAnswer(delayMs: Long, jitter: Long?): String {
        val response = webClient.get(
            "${TestApi.DELAYED_ANSWER_PATH}?delayMs=${delayMs}${jitter?.let { "&jitter=${it}" } ?: ""}"
        ).aggregate().await()
        return response.contentUtf8()
    }

    override suspend fun delayedParts(partsCount: Int, partSizeInBytes: Int, delayBetweenPartsMs: Long?): ResponseBody {
        val path = TestApi.DELAYED_PARTS_PATH +
                "?partsCount=${partsCount}" +
                "&partSize=${partSizeInBytes}" +
                (delayBetweenPartsMs?.let { "&delayBetweenPartsMs=$it" } ?: "")
        val response = webClient.execute(
            RequestHeaders.of(
                HttpMethod.GET,
                path,
                HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_OCTET_STREAM
            )
        )
        response.subscribe(NoopSubscriber.get())
        response.whenComplete().await()
        return ResponseBody.create(
            OCTET_STREAM_MEDIA_TYPE,
            0,
            EMPTY_BUFFER
        )
    }

}