package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TasteProfileEntity
import com.acgcompass.data.local.entity.TasteTagStatEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the taste profile (口味画像): the [TasteProfileEntity] aggregate
 * plus its per-tag [TasteTagStatEntity] rows. Low sample sizes surface as low
 * confidence , handled by the domain layer.
 */
@Dao
interface TasteDao {

    // --- Profiles ----------------------------------------------------------

    @Query("SELECT * FROM taste_profiles ORDER BY generatedAt DESC")
    fun observeProfiles(): Flow<List<TasteProfileEntity>>

    /** One-shot snapshot of every taste profile — used by backup export . */
    @Query("SELECT * FROM taste_profiles")
    suspend fun getAllProfiles(): List<TasteProfileEntity>

    @Query("SELECT * FROM taste_profiles ORDER BY generatedAt DESC LIMIT 1")
    fun observeLatestProfile(): Flow<TasteProfileEntity?>

    @Query("SELECT * FROM taste_profiles WHERE id = :id")
    suspend fun getProfile(id: String): TasteProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: TasteProfileEntity)

    @Upsert
    suspend fun upsertProfile(profile: TasteProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: TasteProfileEntity)

    // --- Per-tag statistics ------------------------------------------------

    @Query("SELECT * FROM taste_tag_stats WHERE profileId = :profileId")
    fun observeTagStats(profileId: String): Flow<List<TasteTagStatEntity>>

    /** One-shot snapshot of every taste tag stat — used by backup export . */
    @Query("SELECT * FROM taste_tag_stats")
    suspend fun getAllTagStats(): List<TasteTagStatEntity>

    @Query("SELECT * FROM taste_tag_stats WHERE profileId = :profileId")
    suspend fun getTagStats(profileId: String): List<TasteTagStatEntity>

    @Upsert
    suspend fun upsertTagStats(stats: List<TasteTagStatEntity>)

    @Query("DELETE FROM taste_tag_stats WHERE profileId = :profileId")
    suspend fun deleteTagStatsForProfile(profileId: String)
}
