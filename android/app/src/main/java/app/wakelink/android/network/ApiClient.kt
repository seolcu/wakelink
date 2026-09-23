package app.wakelink.android.network

import app.wakelink.android.BuildConfig
import app.wakelink.android.data.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object ApiClient {

    fun create(tokenStore: TokenStore, baseUrl: String = BuildConfig.DEFAULT_SERVER_BASE_URL): WakeLinkApi {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

        val authInterceptor = Interceptor { chain ->
            val builder = chain.request().newBuilder()
            tokenStore.apiKey?.let { key -> builder.addHeader("Authorization", "Bearer $key") }
            chain.proceed(builder.build())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WakeLinkApi::class.java)
    }
}
