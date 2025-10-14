/*
 * Copyright The Datadatdat Project Contributors
 */
package com.datadatdat.plugin.remote

import io.grpc.ManagedChannel
import com.datadatdat.plugin.PluginProvider

class RemoteProvider(
    pluginDirectory: String,
) : PluginProvider(pluginDirectory) {
    private val magicCookieKey = "datadatdat"
    private val magicCookieValue = "dba4fe2b-56ff-4a16-9bfc-bf651b8f12d6"

    data class LoadedPlugin(
        val process: Process,
        val channel: ManagedChannel,
        val client: RemoteClient,
    )

    private val loadedPlugins: MutableMap<String, LoadedPlugin> = mutableMapOf()

    fun startProcess(pluginName: String): Process = startProcess(pluginName, magicCookieKey, magicCookieValue)

    private fun loadOne(pluginName: String): LoadedPlugin {
        val p = startProcess(pluginName)
        val header = getHeader(p)
        val channel = getManagedChannel(header)
        val stub = RemoteGrpc.newBlockingStub(channel)
        val client = RemoteClient(stub)
        return LoadedPlugin(p, channel, client)
    }

    @Synchronized
    fun load(pluginName: String): Remote {
        if (!loadedPlugins.containsKey(pluginName)) {
            loadedPlugins[pluginName] = loadOne(pluginName)
        } else if (!loadedPlugins[pluginName]!!.process.isAlive) {
            unload(pluginName)
            loadedPlugins[pluginName] = loadOne(pluginName)
        }

        return loadedPlugins[pluginName]!!.client
    }

    @Synchronized
    fun unload(pluginName: String) {
        if (loadedPlugins.containsKey(pluginName)) {
            val lp = loadedPlugins[pluginName]!!
            lp.channel.shutdownNow()
            lp.process.destroyForcibly()
            loadedPlugins.remove(pluginName)
        }
    }
}
