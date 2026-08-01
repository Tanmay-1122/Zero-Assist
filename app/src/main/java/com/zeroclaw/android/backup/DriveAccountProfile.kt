package com.zeroclaw.android.backup

import java.util.Locale
import org.json.JSONObject

/**
 * User-facing Google Drive account details for backup UI surfaces.
 *
 * @property email The email address associated with the signed-in Google account.
 * @property displayName The display name reported by Google, if available.
 */
data class DriveAccountProfile(
    val email: String,
    val displayName: String? = null,
) {
    val preferredName: String
        get() = derivePreferredDriveName(displayName, email)
}

internal fun derivePreferredDriveName(
    displayName: String?,
    email: String?,
): String {
    val normalizedDisplayName = displayName?.trim().orEmpty()
    if (normalizedDisplayName.isNotBlank()) {
        return normalizedDisplayName
    }

    val localPart = email?.substringBefore('@')?.trim().orEmpty()
    val normalizedLocalPart =
        localPart
            .replace(Regex("[._-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    if (normalizedLocalPart.isBlank()) {
        return FALLBACK_DRIVE_USER_NAME
    }

    return normalizedLocalPart
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token
                .lowercase(Locale.getDefault())
                .replaceFirstChar { first ->
                    if (first.isLowerCase()) {
                        first.titlecase(Locale.getDefault())
                    } else {
                        first.toString()
                    }
                }
        }
        .ifBlank { FALLBACK_DRIVE_USER_NAME }
}

internal fun identityHasUserName(identityJson: String): Boolean {
    if (identityJson.isBlank()) return false
    return runCatching {
        val root = JSONObject(identityJson)
        val identity = root.optJSONObject("identity")
        val nestedUserName = identity?.optString("user_name", "").orEmpty().trim()
        val topLevelUserName = root.optString("user_name", "").trim()
        nestedUserName.isNotBlank() || topLevelUserName.isNotBlank()
    }.getOrDefault(false)
}

internal fun seedUserNameIntoIdentityJson(
    identityJson: String,
    userName: String,
): String {
    val normalizedUserName = userName.trim()
    if (normalizedUserName.isBlank()) return identityJson

    val root = runCatching { JSONObject(identityJson) }.getOrDefault(JSONObject())
    val identity = root.optJSONObject("identity") ?: JSONObject()
    if (identity.optString("user_name", "").trim().isNotBlank()) {
        return root.toString()
    }

    identity.put("user_name", normalizedUserName)
    root.put("identity", identity)
    root.remove("user_name")
    return root.toString()
}

private const val FALLBACK_DRIVE_USER_NAME = "User"
