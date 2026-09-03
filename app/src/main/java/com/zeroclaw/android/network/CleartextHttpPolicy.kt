/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.network

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Runtime guard for the app-wide cleartext manifest exception.
 *
 * Android Network Security Config cannot express private-IP CIDR exceptions,
 * so the manifest must still permit cleartext for LAN model discovery. All
 * application-owned URL callers should pass through this policy before opening
 * HTTP connections so cleartext stays scoped to loopback and local-network
 * endpoints.
 */
object CleartextHttpPolicy {
    /**
     * Returns true when the URL is either HTTPS or an HTTP URL targeting a
     * loopback/private/local host.
     */
    fun isUrlAllowed(rawUrl: String): Boolean {
        val uri = rawUrl.toUriOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (uri.rawUserInfo != null) return false
        return when (scheme) {
            "https" -> true
            "http" -> uri.host?.let(::isLocalHttpHost) == true
            else -> false
        }
    }

    /**
     * Throws a network-shaped error when [rawUrl] would use cleartext outside
     * the approved local scope.
     */
    @Throws(IOException::class)
    fun requireAllowed(
        rawUrl: String,
        caller: String,
    ) {
        if (!isUrlAllowed(rawUrl)) {
            throw IOException(
                "$caller blocked cleartext or unsupported URL outside local network scope",
            )
        }
    }

    /**
     * Returns true for hosts that are acceptable cleartext targets.
     */
    internal fun isLocalHttpHost(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase(Locale.US)
        if (normalized.isBlank()) return false
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        if (LOCAL_DNS_SUFFIXES.any { normalized.endsWith(it) }) return true
        if (isPrivateIpv4Literal(normalized)) return true
        return isLocalIpv6Literal(normalized)
    }

    private fun isPrivateIpv4Literal(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != IPV4_OCTET_COUNT) return false
        val octets =
            parts.map { part ->
                part.toIntOrNull()?.takeIf { it in IPV4_OCTET_RANGE } ?: return false
            }
        val first = octets[0]
        val second = octets[1]
        return first == LOOPBACK_IPV4_PREFIX ||
            first == LINK_LOCAL_IPV4_PREFIX && second == LINK_LOCAL_IPV4_SECOND ||
            first == PRIVATE_10_PREFIX ||
            first == PRIVATE_172_PREFIX && second in PRIVATE_172_SECOND_RANGE ||
            first == PRIVATE_192_PREFIX && second == PRIVATE_192_SECOND
    }

    private fun isLocalIpv6Literal(host: String): Boolean =
        host == IPV6_LOOPBACK ||
            host.startsWith(IPV6_LINK_LOCAL_PREFIX) ||
            host.startsWith(IPV6_UNIQUE_LOCAL_FC_PREFIX) ||
            host.startsWith(IPV6_UNIQUE_LOCAL_FD_PREFIX)

    private fun String.toUriOrNull(): URI? =
        try {
            URI(this.trim())
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: URISyntaxException) {
            null
        }

    private val LOCAL_DNS_SUFFIXES = listOf(".local", ".lan", ".home")
    private val IPV4_OCTET_RANGE = 0..255
    private val PRIVATE_172_SECOND_RANGE = 16..31
    private const val IPV4_OCTET_COUNT = 4
    private const val LOOPBACK_IPV4_PREFIX = 127
    private const val LINK_LOCAL_IPV4_PREFIX = 169
    private const val LINK_LOCAL_IPV4_SECOND = 254
    private const val PRIVATE_10_PREFIX = 10
    private const val PRIVATE_172_PREFIX = 172
    private const val PRIVATE_192_PREFIX = 192
    private const val PRIVATE_192_SECOND = 168
    private const val IPV6_LOOPBACK = "::1"
    private const val IPV6_LINK_LOCAL_PREFIX = "fe80:"
    private const val IPV6_UNIQUE_LOCAL_FC_PREFIX = "fc"
    private const val IPV6_UNIQUE_LOCAL_FD_PREFIX = "fd"
}
