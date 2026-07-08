// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.plugin.remote

import dev.dit.plugin.PluginProvider
import io.grpc.ManagedChannel
import io.netty.channel.EventLoopGroup

class RemoteProvider(
    pluginDirectory: String,
) : PluginProvider(pluginDirectory) {
    private val magicCookieKey = "dit"
    private val magicCookieValue = "dba4fe2b-56ff-4a16-9bfc-bf651b8f12d6"

    /**
     * State held for a loaded plugin. [eventLoopGroup] is non-null when the plugin
     * uses a UDS channel (epoll/kqueue) and must be shut down on [unload] to release
     * Netty threads.
     */
    data class LoadedPlugin(
        val process: Process,
        val channel: ManagedChannel,
        val client: RemoteClient,
        val eventLoopGroup: EventLoopGroup?,
    )

    private val loadedPlugins: MutableMap<String, LoadedPlugin> = mutableMapOf()

    fun startProcess(pluginName: String): Process = startProcess(pluginName, magicCookieKey, magicCookieValue)

    private fun loadOne(pluginName: String): LoadedPlugin {
        val p = startProcess(pluginName)
        val header = getHeader(p)
        val pluginChannel = getManagedChannel(header)
        val stub = RemoteGrpc.newBlockingStub(pluginChannel.channel)
        val client = RemoteClient(stub)
        return LoadedPlugin(p, pluginChannel.channel, client, pluginChannel.eventLoopGroup)
    }

    @Synchronized
    fun load(pluginName: String): Remote {
        val existing = loadedPlugins[pluginName]
        val loaded =
            when {
                existing == null -> {
                    loadOne(pluginName).also { loadedPlugins[pluginName] = it }
                }

                !existing.process.isAlive -> {
                    unload(pluginName)
                    loadOne(pluginName).also { loadedPlugins[pluginName] = it }
                }

                else -> {
                    existing
                }
            }
        return loaded.client
    }

    @Synchronized
    fun unload(pluginName: String) {
        loadedPlugins[pluginName]?.let { lp ->
            lp.channel.shutdownNow()
            // Release Netty threads associated with UDS channels; TCP channels have no group.
            lp.eventLoopGroup?.shutdownGracefully()
            lp.process.destroyForcibly()
            loadedPlugins.remove(pluginName)
        }
    }

    /**
     * Visible-for-test snapshot of currently loaded plugins, used by tests to assert
     * lifecycle behavior (e.g. that EventLoopGroups are shut down on [unload]).
     */
    @Synchronized
    internal fun loadedPluginsSnapshot(): Map<String, LoadedPlugin> = loadedPlugins.toMap()
}
