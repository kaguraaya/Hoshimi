package com.acgcompass.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Non-sensitive credential metadata for UI status display only.
 *
 * Credential plaintext (key/token/secret) is never stored in
 * Room. This entity holds only whether a source is [configured], its [status]
 * label and the [lastTestedAt] timestamp. Actual secrets live exclusively in the
 * encrypted credential store (EncryptedSharedPreferences / Keystore).
 */
@Entity(tableName = "credential_meta")
data class CredentialMetaEntity(
    @PrimaryKey
    val sourceId: String,
    val configured: Boolean,
    val status: String,
    val lastTestedAt: Long?,
)
