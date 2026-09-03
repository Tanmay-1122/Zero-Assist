/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/** Deterministic UI hints for messaging apps that Zero-Assist can safely automate. */
data class MessagingAppUiProfile(
    val packageName: String,
    val displayName: String,
    val deterministicSendEnabled: Boolean,
    val disabledReason: String? = null,
    val searchLabels: Set<String>,
    val sendButtonLabels: Set<String>,
    val draftViewIdHints: List<String>,
    val headerViewIdHints: List<String>,
    val listOrSearchResultViewIdHints: List<String>,
    val conversationEntryLabels: Set<String>,
    val conversationEntryViewIdHints: List<String>,
    val genericWindowTitles: Set<String>,
)

/** Registry for production-enabled deterministic messaging profiles. */
object MessagingAppUiProfiles {
    val WhatsApp =
        MessagingAppUiProfile(
            packageName = WHATSAPP_PACKAGE_NAME,
            displayName = "WhatsApp",
            deterministicSendEnabled = true,
            searchLabels = setOf("search"),
            sendButtonLabels = setOf("send"),
            draftViewIdHints = listOf("compose", "draft", "edit", "entry", "input", "message"),
            headerViewIdHints =
                listOf(
                    "actionbar",
                    "chat_title",
                    "conversation",
                    "contact_name",
                    "header",
                    "name",
                    "profile",
                    "title",
                    "toolbar",
                ),
            listOrSearchResultViewIdHints = listOf("contact_row", "list", "recycler", "result", "row", "search_result"),
            conversationEntryLabels = emptySet(),
            conversationEntryViewIdHints = emptyList(),
            genericWindowTitles = setOf("chat", "chats", "search", "whatsapp"),
        )

    val Telegram =
        disabledMessagingProfile(
            packageName = TELEGRAM_PACKAGE_NAME,
            displayName = "Telegram",
        )

    val Messages =
        disabledMessagingProfile(
            packageName = MESSAGES_PACKAGE_NAME,
            displayName = "Messages",
        )

    val Signal =
        disabledMessagingProfile(
            packageName = SIGNAL_PACKAGE_NAME,
            displayName = "Signal",
        )

    val Instagram =
        MessagingAppUiProfile(
            packageName = INSTAGRAM_PACKAGE_NAME,
            displayName = "Instagram",
            deterministicSendEnabled = true,
            searchLabels = setOf("search"),
            sendButtonLabels = setOf("send"),
            draftViewIdHints =
                listOf(
                    "composer",
                    "compose",
                    "draft",
                    "edit",
                    "entry",
                    "input",
                    "row_thread_composer_edittext",
                    "thread_composer",
                ),
            headerViewIdHints =
                listOf(
                    "actionbar",
                    "conversation",
                    "direct_thread_title",
                    "header",
                    "name",
                    "profile",
                    "thread_title",
                    "title",
                    "toolbar",
                ),
            listOrSearchResultViewIdHints =
                listOf(
                    "direct_inbox_row",
                    "list",
                    "recycler",
                    "recipient",
                    "result",
                    "row",
                    "search_result",
                    "thread_list",
                    "user_row",
                ),
            conversationEntryLabels = setOf("chats", "direct messages", "inbox", "messages", "messenger"),
            conversationEntryViewIdHints = listOf("direct", "direct_inbox", "inbox", "messenger"),
            genericWindowTitles = setOf("chats", "direct", "inbox", "instagram", "messages", "messenger", "search"),
        )

    val all: List<MessagingAppUiProfile> =
        listOf(
            WhatsApp,
            Telegram,
            Messages,
            Signal,
            Instagram,
        )

    fun forPackageName(packageName: String?): MessagingAppUiProfile? =
        profileForPackageName(packageName)
            ?.takeIf { it.deterministicSendEnabled }

    fun profileForPackageName(packageName: String?): MessagingAppUiProfile? {
        val normalized = packageName?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return null
        return all.firstOrNull { it.packageName == normalized }
    }

    fun unsupportedSendReason(packageName: String?): String =
        profileForPackageName(packageName)
            ?.disabledReason
            ?: "Unsupported messaging app for deterministic send_message."

    private fun disabledMessagingProfile(
        packageName: String,
        displayName: String,
    ): MessagingAppUiProfile =
        MessagingAppUiProfile(
            packageName = packageName,
            displayName = displayName,
            deterministicSendEnabled = false,
            disabledReason =
                "$displayName deterministic sends are disabled until the WhatsApp real-device checklist is passing reliably.",
            searchLabels = setOf("search"),
            sendButtonLabels = setOf("send"),
            draftViewIdHints = listOf("compose", "draft", "edit", "entry", "input", "message"),
            headerViewIdHints = listOf("conversation", "contact", "header", "name", "profile", "title", "toolbar"),
            listOrSearchResultViewIdHints = listOf("contact_row", "list", "recycler", "result", "row", "search_result"),
            conversationEntryLabels = emptySet(),
            conversationEntryViewIdHints = emptyList(),
            genericWindowTitles = setOf("chat", "chats", "search", displayName.lowercase()),
        )
}

private const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"
private const val TELEGRAM_PACKAGE_NAME = "org.telegram.messenger"
private const val MESSAGES_PACKAGE_NAME = "com.google.android.apps.messaging"
private const val SIGNAL_PACKAGE_NAME = "org.thoughtcrime.securesms"
private const val INSTAGRAM_PACKAGE_NAME = "com.instagram.android"
