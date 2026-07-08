// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.plugin.remote

interface Remote {
    fun type(): String

    fun fromURL(
        url: String,
        properties: Map<String, String>,
    ): Map<String, Any>

    fun toURL(properties: Map<String, Any>): Pair<String, Map<String, String>>

    fun getParameters(properties: Map<String, Any>): Map<String, Any>
}
