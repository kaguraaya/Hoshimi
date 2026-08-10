package com.acgcompass.data.local.converter

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room [TypeConverter]s for ACG Compass entities.
 *
 * Handles the only non-primitive persistence needs in the ER model:
 * `List<String>` <-> `String` for serialized collections such as
 * `WorkEntity.aliases`, `BacklogItemEntity.moodTags` / `riskTags`,
 * and `TasteProfileEntity.titles`.
 *
 * Enum fields (mediaType, status, priority, category, ...) are persisted as
 * plain `String` columns to match the ER diagram exactly; enum <-> String
 * mapping happens at the Entity <-> domain-model boundary  so the
 * persistence layer stays decoupled from domain enums.
 *
 * this layer holds business data only. No credential plaintext is ever
 * serialized here.
 */
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.let { json.encodeToString(stringListSerializer, it) }

    @TypeConverter
    fun toStringList(value: String?): List<String>? = value?.let { json.decodeFromString(stringListSerializer, it) }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val stringListSerializer = ListSerializer(String.serializer())
    }
}
