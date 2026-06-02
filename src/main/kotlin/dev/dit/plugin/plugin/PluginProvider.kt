package dev.dit.plugin

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.kqueue.KQueueDomainSocketChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.unix.DomainSocketAddress
import java.io.File
import kotlin.IllegalStateException

abstract class PluginProvider(
    val pluginDirectory: String,
) {
    data class Header(
        val coreVersion: Int,
        val protoVersion: Int,
        val network: String,
        val addr: String,
        val protoType: String,
        val serverCert: String,
    )

    /**
     * Wraps a gRPC [ManagedChannel] together with the optional Netty [EventLoopGroup]
     * that backs it. UDS channels create a dedicated event loop group whose threads
     * must be shut down with [EventLoopGroup.shutdownGracefully] when the channel is
     * disposed; otherwise the threads leak on every plugin load/unload cycle.
     * TCP channels do not require a separate event loop group, so [eventLoopGroup]
     * is null in that case.
     */
    data class PluginChannel(
        val channel: ManagedChannel,
        val eventLoopGroup: EventLoopGroup?,
    )

    fun startProcess(
        pluginName: String,
        magicCookieKey: String,
        magicCookieValue: String,
    ): Process {
        val builder =
            ProcessBuilder("$pluginDirectory${File.separator}$pluginName")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
        val env = builder.environment()
        env[magicCookieKey] = magicCookieValue

        return builder.start()
    }

    fun getHeader(process: Process): Header {
        val reader = process.inputStream?.bufferedReader() ?: throw IllegalStateException("failed to get output from plugin process")
        reader.use {
            for (line in reader.lines()) {
                val fields = line.trim().split("|")
                if (fields.size == 6) {
                    return Header(
                        coreVersion = fields[0].toInt(),
                        protoVersion = fields[1].toInt(),
                        network = fields[2],
                        addr = fields[3],
                        protoType = fields[4],
                        serverCert = fields[5],
                    )
                }
            }
            val errText = process.errorStream.bufferedReader().use { it.readText() }
            if (process.isAlive) {
                throw IllegalStateException("failed to find plugin header line: $errText")
            } else {
                throw IllegalStateException("process exited before finding plugin header line: $errText")
            }
        }
    }

    fun getManagedChannel(header: Header): PluginChannel {
        if (header.network == "tcp") {
            val channel =
                ManagedChannelBuilder
                    .forTarget(header.addr)
                    .usePlaintext()
                    .build()
            return PluginChannel(channel, null)
        }

        if (header.network == "unix") {
            /*
             * Java does not support UDS natively, so we have to use OS-specific UDS implementations, either epoll
             * (Linux) or kqueue (MacOS).
             */
            val os = System.getProperty("os.name") ?: throw IllegalStateException("failed to determine OS type")
            if (os.lowercase().contains("mac os x")) {
                val klg = KQueueEventLoopGroup()
                val channel =
                    NettyChannelBuilder
                        .forAddress(DomainSocketAddress(header.addr))
                        .eventLoopGroup(klg)
                        .channelType(KQueueDomainSocketChannel::class.java)
                        .usePlaintext()
                        .build()
                return PluginChannel(channel, klg)
            } else {
                val elg = EpollEventLoopGroup()
                val channel =
                    NettyChannelBuilder
                        .forAddress(DomainSocketAddress(header.addr))
                        .eventLoopGroup(elg)
                        .channelType(EpollDomainSocketChannel::class.java)
                        .usePlaintext()
                        .build()
                return PluginChannel(channel, elg)
            }
        }

        throw IllegalStateException("unknown protocol type '${header.protoType}")
    }
}
