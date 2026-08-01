package com.zeroclaw.android.model

/**
 * Registry of official built-in plugins.
 *
 * These plugins are always present in the database and cannot be
 * uninstalled, only enabled or disabled. Their configuration is stored
 * in [AppSettings] via DataStore rather than the plugin's generic
 * configFields map.
 *
 * Each ID maps to a specific TOML config section that
 * [com.zeroclaw.android.service.ConfigTomlBuilder] emits when the
 * plugin is enabled.
 */
object OfficialPlugins {
    /** Web search tool (DuckDuckGo / Brave). Maps to `[web_search]`. */
    const val WEB_SEARCH = "official-web-search"

    /** Web page content fetcher. Maps to `[web_fetch]`. */
    const val WEB_FETCH = "official-web-fetch"

    /** HTTP request tool for external APIs. Maps to `[http_request]`. */
    const val HTTP_REQUEST = "official-http-request"

    /** Web browser automation tool. Maps to `[browser]`. */
    const val BROWSER = "official-browser"

    /** Composio third-party tool integration. Maps to `[composio]`. */
    const val COMPOSIO = "official-composio"

    /** Shared folder access via Android Storage Access Framework. Maps to `[shared_folder]`. */
    const val SHARED_FOLDER = "official-shared-folder"

    /** Default workflow folder access. Maps to `[workflow_folder]`. */
    const val WORKFLOW_FOLDER = "official-workflow-folder"

    /** Linux Sandbox — self-contained Alpine Linux via proot. Maps to `[sandbox]`. */
    const val LINUX_SANDBOX = "official-linux-sandbox"

    /** Google Workspace — Gmail, Drive, Calendar, Sheets, Docs via gws CLI. Maps to `[google_workspace]`. */
    const val GOOGLE_WORKSPACE = "official-google-workspace"

    /** Termux — Android terminal emulator with package management. Maps to `[termux]`. */
    const val TERMUX = "official-termux"

    /** PRoot Browser — Browser automation in PRoot Linux environment. Maps to `[browser.proot]`. */
    const val PROOT_BROWSER = "official-proot-browser"

    /** Set of all official plugin IDs. */
    val ALL: Set<String> =
        setOf(
            WEB_SEARCH,
            WEB_FETCH,
            HTTP_REQUEST,
            BROWSER,
            COMPOSIO,
            SHARED_FOLDER,
            WORKFLOW_FOLDER,
            LINUX_SANDBOX,
            GOOGLE_WORKSPACE,
            TERMUX,
            PROOT_BROWSER,
        )

    /** Returns true if the given [pluginId] is an official built-in plugin. */
    fun isOfficial(pluginId: String): Boolean = pluginId in ALL
}
