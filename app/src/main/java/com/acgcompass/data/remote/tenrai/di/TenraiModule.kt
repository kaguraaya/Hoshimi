package com.acgcompass.data.remote.tenrai.di

import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.data.remote.tenrai.TenraiApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object TenraiModule {
    @Provides
    @Singleton
    fun provideTenraiApi(retrofitBuilder: Retrofit.Builder): TenraiApi = retrofitBuilder
        .baseUrl(NetworkConstants.TENRAI_BASE_URL)
        .build()
        .create(TenraiApi::class.java)
}
