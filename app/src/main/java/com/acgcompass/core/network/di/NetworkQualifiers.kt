package com.acgcompass.core.network.di

import javax.inject.Qualifier

/**
 * 限定符：基础 REST [okhttp3.OkHttpClient]（Bangumi / Jikan / MAL / VNDB 共用，短超时）。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BaseOkHttpClient

/**
 * 限定符：AI provider 专用 [okhttp3.OkHttpClient]（独立实例，宽松超时 / 重试）。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiOkHttpClient
