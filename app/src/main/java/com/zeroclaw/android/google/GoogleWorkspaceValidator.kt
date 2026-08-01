/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.google

import org.json.JSONObject

/**
 * Input validation and security utilities for the Google Workspace tool.
 *
 * Prevents shell injection by enforcing strict character sets on all
 * parameters before they reach the sandbox shell.
 */
object GoogleWorkspaceValidator {

    /** Valid Google Workspace service names. */
    private val VALID_SERVICES = setOf(
        "drive", "gmail", "calendar", "sheets", "docs", "slides",
        "tasks", "people", "chat", "classroom", "forms", "keep",
        "meet", "events",
    )

    /** Valid HTTP methods for gws CLI. */
    private val VALID_METHODS = setOf(
        "list", "get", "create", "update", "delete", "patch",
    )

    /** Valid output formats. */
    private val VALID_FORMATS = setOf("json", "table", "yaml", "csv")

    /** Regex for valid gws names (lowercase alphanumeric + underscore/hyphen). */
    private val GWS_NAME_REGEX = Regex("^[a-z0-9_-]+$")

    /**
     * Validates a Google Workspace tool request.
     * Returns null if valid, or an error message if invalid.
     */
    fun validate(
        service: String,
        resource: String,
        method: String,
        subResource: String? = null,
        format: String? = null,
    ): String? {
        if (service.isBlank()) return "Missing required field: service"
        if (service !in VALID_SERVICES) {
            return "Invalid service '$service'. Allowed: ${VALID_SERVICES.sorted().joinToString(", ")}"
        }

        if (resource.isBlank()) return "Missing required field: resource"
        if (!GWS_NAME_REGEX.matches(resource)) {
            return "Invalid resource '$resource': only lowercase alphanumeric, underscore, and hyphen are allowed"
        }

        if (method.isBlank()) return "Missing required field: method"
        if (method !in VALID_METHODS) {
            return "Invalid method '$method'. Allowed: ${VALID_METHODS.sorted().joinToString(", ")}"
        }

        if (!subResource.isNullOrBlank()) {
            if (!GWS_NAME_REGEX.matches(subResource)) {
                return "Invalid sub_resource '$subResource': only lowercase alphanumeric, underscore, and hyphen are allowed"
            }
        }

        if (!format.isNullOrBlank() && format !in VALID_FORMATS) {
            return "Invalid format '$format'. Allowed: ${VALID_FORMATS.sorted().joinToString(", ")}"
        }

        return null
    }

    /**
     * Sanitizes a string parameter by removing potentially dangerous characters.
     * Use this as a defense-in-depth measure after validation.
     */
    fun sanitize(value: String): String {
        return value.filter { c ->
            c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'
        }
    }

    /**
     * Builds the gws CLI command arguments from validated parameters.
     * All inputs MUST be validated via [validate] before calling this.
     */
    fun buildCommandArgs(
        service: String,
        resource: String,
        method: String,
        subResource: String? = null,
        params: Map<String, Any>? = null,
        body: Map<String, Any>? = null,
        format: String? = null,
        pageAll: Boolean = false,
        pageLimit: Int? = null,
    ): List<String> {
        val args = mutableListOf(service, resource)
        if (!subResource.isNullOrBlank()) {
            args.add(subResource)
        }
        args.add(method)

        if (params != null && params.isNotEmpty()) {
            args.addAll(listOf("--params", JSONObject(params).toString()))
        }
        if (body != null && body.isNotEmpty()) {
            args.addAll(listOf("--json", JSONObject(body).toString()))
        }
        if (!format.isNullOrBlank()) {
            args.addAll(listOf("--format", format))
        }
        if (pageAll) {
            args.add("--page-all")
            args.addAll(listOf("--page-limit", (pageLimit ?: 10).toString()))
        } else if (pageLimit != null) {
            args.addAll(listOf("--page-limit", pageLimit.toString()))
        }

        return args
    }
}
