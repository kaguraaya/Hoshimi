package com.acgcompass.data.taste

import com.acgcompass.data.local.entity.UserCollectionEntity
import java.security.MessageDigest

internal fun List<UserCollectionEntity>.toTasteInputFingerprint(): String = sortedBy { it.id }
    .joinToString("\n") { collection ->
        listOf(
            collection.id,
            collection.localWorkId,
            collection.sourceItemId,
            collection.status.orEmpty(),
            collection.rating?.toString().orEmpty(),
            collection.comment.orEmpty(),
            collection.tags.map(String::trim).filter(String::isNotBlank).sorted().joinToString(","),
        ).joinToString("\u001f")
    }
    .sha256()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
