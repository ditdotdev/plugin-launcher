package io.titandata.plugin

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.kqueue.KQueueDomainSocketChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.unix.DomainSocketAddress
import java.io.File
import kotlin.IllegalStateException

abstract class PluginProvider(val pluginDirectory: String) {

    data class Header(
        val coreVersion: Int,
        val protoVersion: Int,
        val network: String,
        val addr: String,
        val protoType: String,
        val serverCert: String
    )

    fun startProcess(pluginName: String, magicCookieKey: String, magicCookieValue: String): Process {
        val builder = ProcessBuilder("$pluginDirectory${File.separator}$pluginName")
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
                    process.inputStream?.close()
                    return Header(
                            coreVersion = fields[0].toInt(),
                            protoVersion = fields[1].toInt(),
                            network = fields[2],
                            addr = fields[3],
                            protoType = fields[4],
                            serverCert = fields[5]
                    )
                }
            }
            val errText = process.errorStream.bufferedReader().readText()
            if (process.isAlive) {
                throw IllegalStateException("failed to find plugin header line: $errText")
            } else {
                throw IllegalStateException("process exited before finding plugin header line: $errText")
            }
        }
    }

    fun getManagedChannel(header: Header): ManagedChannel {
        if (header.network == "tcp") {
            return ManagedChannelBuilder.forTarget(header.addr)
                    .usePlaintext()
                    .build()
        }

        if (header.network == "unix") {
            /*
             * Java does not support UDS natively, so we have to use OS-specific UDS implementations, either epoll
             * (Linux) or kqueue (MacOS).
             */
            val os = System.getProperty("os.name") ?: throw IllegalStateException("failed to determine OS type")
            if (os.toLowerCase().contains("mac os x")) {
                val klg = KQueueEventLoopGroup()
                return NettyChannelBuilder
                        .forAddress(DomainSocketAddress(header.addr))
                        .eventLoopGroup(klg)
                        .channelType(KQueueDomainSocketChannel::class.java)
                        .usePlaintext()
                        .build()
            } else {
                val elg = EpollEventLoopGroup()
                return NettyChannelBuilder
                        .forAddress(DomainSocketAddress(header.addr))
                        .eventLoopGroup(elg)
                        .channelType(EpollDomainSocketChannel::class.java)
                        .usePlaintext()
                        .build()
            }
        }

        throw IllegalStateException("unknown protocol type '${header.protoType}")
    }
}
