/*
 * Copyright The Datadatdat Project Contributors
 */
package com.datadatdat.plugin

import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec

class PluginProviderTest : StringSpec() {
    init {
        "Header data class stores fields correctly" {
            val header =
                PluginProvider.Header(
                    coreVersion = 1,
                    protoVersion = 2,
                    network = "tcp",
                    addr = "127.0.0.1:25000",
                    protoType = "grpc",
                    serverCert = "",
                )
            header.coreVersion shouldBe 1
            header.protoVersion shouldBe 2
            header.network shouldBe "tcp"
            header.addr shouldBe "127.0.0.1:25000"
            header.protoType shouldBe "grpc"
            header.serverCert shouldBe ""
        }

        "Header data class equality" {
            val h1 = PluginProvider.Header(1, 1, "tcp", "addr", "grpc", "")
            val h2 = PluginProvider.Header(1, 1, "tcp", "addr", "grpc", "")
            val h3 = PluginProvider.Header(2, 1, "tcp", "addr", "grpc", "")
            h1 shouldBe h2
            (h1 == h3) shouldBe false
        }

        "Header data class copy" {
            val h1 = PluginProvider.Header(1, 1, "tcp", "addr", "grpc", "")
            val h2 = h1.copy(network = "unix")
            h2.network shouldBe "unix"
            h2.coreVersion shouldBe 1
        }

        "getManagedChannel creates TCP channel" {
            val provider = object : PluginProvider("/tmp") {}
            val header = PluginProvider.Header(1, 1, "tcp", "127.0.0.1:99999", "grpc", "")
            val pc = provider.getManagedChannel(header)
            pc.channel shouldNotBe null
            // TCP channels do not allocate a Netty EventLoopGroup.
            pc.eventLoopGroup shouldBe null
            pc.channel.shutdownNow()
        }

        "getManagedChannel throws for unknown protocol" {
            val provider = object : PluginProvider("/tmp") {}
            val header = PluginProvider.Header(1, 1, "websocket", "addr", "grpc", "")
            shouldThrow<IllegalStateException> {
                provider.getManagedChannel(header)
            }
        }

        "PluginChannel data class equality" {
            val provider = object : PluginProvider("/tmp") {}
            val header = PluginProvider.Header(1, 1, "tcp", "127.0.0.1:1", "grpc", "")
            val pc1 = provider.getManagedChannel(header)
            try {
                val pc2 = PluginProvider.PluginChannel(pc1.channel, pc1.eventLoopGroup)
                pc1 shouldBe pc2
            } finally {
                pc1.channel.shutdownNow()
            }
        }

        // Only Linux (epoll) and macOS (kqueue) carry a native UDS transport in this build,
        // and only when the netty-transport-native-* native library version matches the
        // netty-common version pulled in transitively by grpc-netty. The build currently
        // pins netty-transport-native-epoll:4.1.44.Final which is incompatible with the
        // 4.1.132.Final netty-common from grpc-netty 1.81.0, so the epoll native lib
        // fails to load in CI. Until that mismatch is fixed (out of scope here), this
        // UDS-specific lifecycle test runs only on local non-CI Unix-like dev boxes
        // where the developer's environment can supply a working native library.
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val isUnixLike = os.contains("linux") || os.contains("mac os x")
        val isCI = System.getenv("CI") != null
        if (isUnixLike && !isCI) {
            "getManagedChannel allocates an EventLoopGroup for UDS that can be shut down" {
                val provider = object : PluginProvider("/tmp") {}
                // Use a path under java.io.tmpdir that does not need to be bound; the
                // channel is created lazily and we never actually dial the socket.
                val sockPath = java.io.File(System.getProperty("java.io.tmpdir"), "plugin-launcher-test-${System.nanoTime()}.sock").path
                val header = PluginProvider.Header(1, 1, "unix", sockPath, "grpc", "")
                val pc = provider.getManagedChannel(header)
                try {
                    pc.eventLoopGroup shouldNotBe null
                    pc.eventLoopGroup!!.isShutdown shouldBe false
                    // Releasing the group is what RemoteProvider.unload() does to avoid the leak.
                    pc.eventLoopGroup.shutdownGracefully().sync()
                    pc.eventLoopGroup.isShutdown shouldBe true
                } finally {
                    pc.channel.shutdownNow()
                }
            }
        }
    }
}
