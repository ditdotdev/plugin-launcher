// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

/*
 * Copyright The Dit Project Contributors
 */
package dev.dit.plugin.remote

import dev.dit.plugin.StructUtil

class RemoteClient(
    val stub: RemoteGrpc.RemoteBlockingStub,
) : Remote {
    private val structUtil = StructUtil()

    override fun type(): String {
        val req = RemoteProto.GetTypeRequest.newBuilder().build()
        val res = stub.getType(req)
        return res.type
    }

    override fun fromURL(
        url: String,
        properties: Map<String, String>,
    ): Map<String, Any> {
        val req =
            RemoteProto.FromURLRequest
                .newBuilder()
                .setUrl(url)
                .putAllProperties(properties)
                .build()
        val res = stub.fromURL(req)
        return structUtil.structToMap(res.remote)
    }

    override fun toURL(properties: Map<String, Any>): Pair<String, Map<String, String>> {
        val req =
            RemoteProto.ToURLRequest
                .newBuilder()
                .setRemote(structUtil.mapToStruct(properties))
                .build()
        val res = stub.toURL(req)
        return res.url to res.propertiesMap
    }

    override fun getParameters(properties: Map<String, Any>): Map<String, Any> {
        val req =
            RemoteProto.GetParametersRequest
                .newBuilder()
                .setRemote(structUtil.mapToStruct(properties))
                .build()
        val res = stub.getParameters(req)
        return structUtil.structToMap(res.parameters)
    }
}
