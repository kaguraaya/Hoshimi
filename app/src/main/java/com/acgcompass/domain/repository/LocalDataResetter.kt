package com.acgcompass.domain.repository

interface LocalDataResetter {
    suspend fun reset()
}
