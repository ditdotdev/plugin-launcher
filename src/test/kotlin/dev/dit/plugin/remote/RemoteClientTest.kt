// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.plugin.remote

import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class RemoteClientTest : StringSpec() {
    init {
        "type returns remote type string" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()
            val response =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("s3")
                    .build()
            every { stub.getType(any()) } returns response

            val client = RemoteClient(stub)
            client.type() shouldBe "s3"

            verify { stub.getType(any()) }
        }

        "fromURL returns parsed properties" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()

            val remoteStruct =
                Struct
                    .newBuilder()
                    .putFields("bucket", Value.newBuilder().setStringValue("my-bucket").build())
                    .putFields("region", Value.newBuilder().setStringValue("us-west-2").build())
                    .build()

            val response =
                RemoteProto.FromURLResponse
                    .newBuilder()
                    .setRemote(remoteStruct)
                    .build()
            every { stub.fromURL(any()) } returns response

            val client = RemoteClient(stub)
            val result = client.fromURL("s3://my-bucket", mapOf("region" to "us-west-2"))

            result.size shouldBe 2
            result["bucket"] shouldBe "my-bucket"
            result["region"] shouldBe "us-west-2"
        }

        "toURL returns url and properties pair" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()

            val response =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("s3://my-bucket")
                    .putProperties("region", "us-west-2")
                    .build()
            every { stub.toURL(any()) } returns response

            val client = RemoteClient(stub)
            val result = client.toURL(mapOf("bucket" to "my-bucket"))

            result.first shouldBe "s3://my-bucket"
            result.second["region"] shouldBe "us-west-2"
        }

        "getParameters returns parameter map" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()

            val paramStruct =
                Struct
                    .newBuilder()
                    .putFields("endpoint", Value.newBuilder().setStringValue("https://s3.amazonaws.com").build())
                    .build()

            val response =
                RemoteProto.GetParametersResponse
                    .newBuilder()
                    .setParameters(paramStruct)
                    .build()
            every { stub.getParameters(any()) } returns response

            val client = RemoteClient(stub)
            val result = client.getParameters(mapOf("bucket" to "test"))

            result["endpoint"] shouldBe "https://s3.amazonaws.com"
        }

        "type propagates gRPC exception" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()
            every { stub.getType(any()) } throws io.grpc.StatusRuntimeException(io.grpc.Status.UNAVAILABLE)

            val client = RemoteClient(stub)
            shouldThrow<io.grpc.StatusRuntimeException> {
                client.type()
            }
        }

        "fromURL propagates gRPC exception" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()
            every { stub.fromURL(any()) } throws io.grpc.StatusRuntimeException(io.grpc.Status.INTERNAL)

            val client = RemoteClient(stub)
            shouldThrow<io.grpc.StatusRuntimeException> {
                client.fromURL("s3://bucket", emptyMap())
            }
        }

        "toURL with empty properties" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()

            val response =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("echo://echo")
                    .build()
            every { stub.toURL(any()) } returns response

            val client = RemoteClient(stub)
            val result = client.toURL(emptyMap())

            result.first shouldBe "echo://echo"
            result.second.size shouldBe 0
        }

        "getParameters with empty properties" {
            val stub = mockk<RemoteGrpc.RemoteBlockingStub>()

            val response =
                RemoteProto.GetParametersResponse
                    .newBuilder()
                    .setParameters(Struct.getDefaultInstance())
                    .build()
            every { stub.getParameters(any()) } returns response

            val client = RemoteClient(stub)
            val result = client.getParameters(emptyMap())

            result.size shouldBe 0
        }
    }
}
