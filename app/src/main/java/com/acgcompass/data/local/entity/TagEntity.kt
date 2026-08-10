package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tag taxonomy entry. [category] maps to TagCategory
 * (CONTENT_TYPE / STATUS / LENGTH / MOOD / RISK) stored as String.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["category", "name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey
    val id: String,
    val category: String,
    val name: String,
)
