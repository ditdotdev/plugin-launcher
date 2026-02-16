/*
 * Copyright The Datadatdat Project Contributors
 */
package com.datadatdat.plugin

import io.kotlintest.shouldBe
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
            val channel = provider.getManagedChannel(header)
            channel.shutdownNow()
        }

        "getManagedChannel throws for unknown protocol" {
            val provider = object : PluginProvider("/tmp") {}
            val header = PluginProvider.Header(1, 1, "websocket", "addr", "grpc", "")
            shouldThrow<IllegalStateException> {
                provider.getManagedChannel(header)
            }
        }
    }
}
