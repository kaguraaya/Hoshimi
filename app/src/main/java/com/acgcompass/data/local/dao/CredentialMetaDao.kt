package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.CredentialMetaEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for non-sensitive credential metadata used only for UI status display.
 *
 * Credential plaintext is never persisted in Room. This DAO
 * only ever touches [CredentialMetaEntity] (configured flag, status label, last
 * tested timestamp). Secrets live exclusively in the encrypted credential store.
 */
@Dao
interface CredentialMetaDao {

    @Query("SELECT * FROM credential_meta")
    fun observeAll(): Flow<List<CredentialMetaEntity>>

    @Query("SELECT * FROM credential_meta WHERE sourceId = :sourceId")
    fun observeBySource(sourceId: String): Flow<CredentialMetaEntity?>

    @Query("SELECT * FROM credential_meta WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: String): CredentialMetaEntity?

    @Upsert
    suspend fun upsert(meta: CredentialMetaEntity)

    @Delete
    suspend fun delete(meta: CredentialMetaEntity)

    @Query("DELETE FROM credential_meta WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)
}
