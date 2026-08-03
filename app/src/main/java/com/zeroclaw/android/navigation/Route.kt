/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe route definitions for the application navigation graph.
 *
 * Each route is a [Serializable] object or data class that the Navigation
 * Compose library uses for type-safe argument passing between destinations.
 *
 * Dashboard home screen showing daemon status overview.
 */
@Serializable
data object DashboardRoute


/** Connections hub screen with tabs for Active, Agents, Plugins, and Channels. */
@Serializable
data class ConnectionsHubRoute(val tabIndex: Int = 0)

/** Group chat screen for the active multi-agent workspace. */
@Serializable
data class AgentGroupChatRoute(
    val highlightedAgentId: String? = null,
    val familyId: String = "Active",
    val initialInput: String? = null,
)

/** Agent detail screen. */
@Serializable
data class AgentDetailRoute(
    /** Unique identifier of the agent to display. */
    val agentId: String,
)

/** Add new agent wizard screen. */
@Serializable
data object AddAgentRoute


/** Plugin detail screen. */
@Serializable
data class PluginDetailRoute(
    /** Unique identifier of the plugin to display. */
    val pluginId: String,
)

/** Root settings screen. */
@Serializable
data object SettingsRoute

/** Service configuration sub-screen. */
@Serializable
data object ServiceConfigRoute

/** Battery settings sub-screen. */
@Serializable
data object BatterySettingsRoute

/** About information sub-screen. */
@Serializable
data object AboutRoute

/** Updates check sub-screen. */
@Serializable
data object UpdatesRoute

/** API key management sub-screen. */
@Serializable
data object ApiKeysRoute

/**
 * API key detail sub-screen.
 *
 * @property keyId Identifier of the key to edit, or null for adding a new key.
 */
@Serializable
data class ApiKeyDetailRoute(
    val keyId: String? = null,
)

/** Log viewer sub-screen. */
@Serializable
data object LogViewerRoute

/** Agent identity (AIEOS) editor sub-screen. */
@Serializable
data object IdentityRoute

/** Connected channels management sub-screen. */
@Serializable
data object ConnectedChannelsRoute

/**
 * Channel detail sub-screen.
 *
 * @property channelId Identifier of the channel to edit, or null for adding a new channel.
 * @property channelType Channel type name for new channel creation (used when channelId is null).
 */
@Serializable
data class ChannelDetailRoute(
    val channelId: String? = null,
    val channelType: String? = null,
)

/** Interactive terminal REPL screen. */
@Serializable
data class TerminalRoute(
    val initialInput: String? = null,
)

/** Zero-Assist Doctor diagnostics screen. */
@Serializable
data object DoctorRoute

/** Autonomy level and security policy screen. */
@Serializable
data object AutonomyRoute

/** Security posture overview screen. */
@Serializable
data object SecurityOverviewRoute

/** Tunnel configuration screen. */
@Serializable
data object TunnelRoute

/** Gateway and pairing configuration screen. */
@Serializable
data object GatewayRoute

/** Embedded web dashboard screen (WebView). */
@Serializable
data object WebDashboardRoute

/** Model routing rules screen. */
@Serializable
data object ModelRoutesRoute

/** Memory advanced configuration screen. */
@Serializable
data object MemoryAdvancedRoute

/** Observability backend configuration screen. */
@Serializable
data object ObservabilityRoute

/** Offline voice assistant and local voice library settings screen. */
@Serializable
data object VoiceAssistantSettingsRoute

/** Plugin registry sync settings screen. */
@Serializable
data object PluginRegistryRoute

/** QR code scanner screen for gateway pairing. */
@Serializable
data object QrScannerRoute

/** Cost tracking detail screen. */
@Serializable
data object CostDetailRoute

/** Memory entries browser screen. */
@Serializable
data object MemoryBrowserRoute

/** Advanced security settings (sandbox, OTP, e-stop). */
@Serializable
data object SecurityAdvancedRoute

/** Embedding routes configuration screen. */
@Serializable
data object EmbeddingRoutesRoute

/** First-run onboarding wizard. */
@Serializable
data object OnboardingRoute

/** Auth profiles management sub-screen. */
@Serializable
data object AuthProfilesRoute

/** Spotify account-linking sub-screen. */
@Serializable
data object SpotifyAccountRoute

/** Placeholder skill permissions screen for future Rhai capability grants. */
@Serializable
data object SkillPermissionsRoute

/** Post-onboarding daemon setup and channel initialization screen. */
@Serializable
data object SetupRoute

// ====== Hardware Expansion Routes ======

/** Hardware devices management hub screen. */
@Serializable
data object HardwareDevicesRoute

/** GPIO pin configuration and control screen. */
@Serializable
data class GpioPinControlRoute(val deviceId: String)

/** Sensor readings monitoring screen. */
@Serializable
data class SensorMonitorRoute(val deviceId: String)

/** Sensor alert configuration screen. */
@Serializable
data class SensorAlertConfigRoute(val deviceId: String)

/** Actuator command scheduling and control screen. */
@Serializable
data class ActuatorControlRoute(val deviceId: String)

/** Hardware audit logs and diagnostics screen. */
@Serializable
data class HardwareHealthRoute(val deviceId: String)

/** On-device LiteRT LM model catalog and management screen. */
@Serializable
data object LiteRTModelsRoute

/** Google Workspace settings sub-screen. */
@Serializable
data object GoogleWorkspaceSettingsRoute
