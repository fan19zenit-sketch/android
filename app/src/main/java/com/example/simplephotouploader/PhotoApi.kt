package com.example.simplephotouploader

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object PhotoApi {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("X-App-Version", BuildConfig.VERSION_NAME)
                .header("X-App-Version-Code", BuildConfig.VERSION_CODE.toString())
                .header("X-App-Package", BuildConfig.APPLICATION_ID)
                .header("User-Agent", "PhotoVChistotu/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .build())
        }.build()
}
