/*
 * Copyright The Datadatdat Project Contributors
 */
package com.datadatdat.plugin.remote

interface Remote {
    fun type(): String

    fun fromURL(
        url: String,
        properties: Map<String, String>,
    ): Map<String, Any>

    fun toURL(properties: Map<String, Any>): Pair<String, Map<String, String>>

    fun getParameters(properties: Map<String, Any>): Map<String, Any>
}
