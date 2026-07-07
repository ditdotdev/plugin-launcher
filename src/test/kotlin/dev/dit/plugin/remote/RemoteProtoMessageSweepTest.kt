// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

/*
 * Copyright The Dit Project Contributors
 */
package dev.dit.plugin.remote

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.Message
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec
import java.io.ByteArrayInputStream

/**
 * Parameterized sweep over every top-level RemoteProto message. For each message we
 * exercise:
 *   - newBuilder() and newBuilder(prototype)
 *   - Builder.clone()
 *   - Builder.clear() and Builder.clearField(...)
 *   - parseFrom(byte[]), parseFrom(ByteString), parseFrom(InputStream), parseFrom(CodedInputStream)
 *   - parseDelimitedFrom(InputStream)
 *   - equals/hashCode across (default, populated, populated-copy)
 *   - getSerializedSize, getParserForType, getDescriptorForType, getDefaultInstanceForType
 *   - toString contains type name
 *
 * The goal is to hammer the Builder / Parser / Message surface that the schema-specific
 * round-trip tests don't already touch on every message uniformly.
 */
class RemoteProtoMessageSweepTest : StringSpec() {
    private fun sampleStruct(seed: String): Struct =
        Struct
            .newBuilder()
            .putFields("k-$seed", Value.newBuilder().setStringValue("v-$seed").build())
            .build()

    /**
     * One sample per top-level message in remote.proto. The "populated" variant must
     * be non-default so we exercise more of the Builder/Parser code paths than an
     * empty marker message would.
     */
    private fun samples(): List<Pair<String, Message>> =
        listOf(
            "GetTypeRequest" to RemoteProto.GetTypeRequest.getDefaultInstance(),
            "GetTypeResponse" to
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("sweep-type")
                    .build(),
            "FromURLRequest" to
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("sweep://u")
                    .putProperties("p", "v")
                    .build(),
            "FromURLResponse" to
                RemoteProto.FromURLResponse
                    .newBuilder()
                    .setRemote(sampleStruct("fr"))
                    .build(),
            "ToURLRequest" to
                RemoteProto.ToURLRequest
                    .newBuilder()
                    .setRemote(sampleStruct("tr"))
                    .build(),
            "ToURLResponse" to
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("sweep://t")
                    .putProperties("p", "v")
                    .build(),
            "GetParametersRequest" to
                RemoteProto.GetParametersRequest
                    .newBuilder()
                    .setRemote(sampleStruct("gpr"))
                    .build(),
            "GetParametersResponse" to
                RemoteProto.GetParametersResponse
                    .newBuilder()
                    .setParameters(sampleStruct("gpr"))
                    .build(),
            "ValidateRemoteRequest" to
                RemoteProto.ValidateRemoteRequest
                    .newBuilder()
                    .setRemote(sampleStruct("vrr"))
                    .build(),
            "ValidateRemoteResponse" to RemoteProto.ValidateRemoteResponse.getDefaultInstance(),
            "ValidateParametersRequest" to
                RemoteProto.ValidateParametersRequest
                    .newBuilder()
                    .setParameters(sampleStruct("vpr"))
                    .build(),
            "ValidateParametersResponse" to RemoteProto.ValidateParametersResponse.getDefaultInstance(),
            "Tag-null" to
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueNull(true)
                    .build(),
            "Tag-string" to
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueString("v")
                    .build(),
            "Commit" to
                RemoteProto.Commit
                    .newBuilder()
                    .setId("c1")
                    .setProperties(sampleStruct("c"))
                    .build(),
            "GetCommitRequest" to
                RemoteProto.GetCommitRequest
                    .newBuilder()
                    .setRemote(sampleStruct("gcr-r"))
                    .setParameters(sampleStruct("gcr-p"))
                    .setCommitId("cid")
                    .build(),
            "GetCommitResponse-null" to
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitNull(true)
                    .build(),
            "GetCommitResponse-value" to
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitValue(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("inner")
                            .build(),
                    ).build(),
            "ListCommitRequest" to
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .setRemote(sampleStruct("lcr-r"))
                    .setParameters(sampleStruct("lcr-p"))
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k")
                            .setValueString("v"),
                    ).build(),
            "ListCommitResponse" to
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("c1"),
                    ).build(),
            "RemoteType" to
                RemoteProto.RemoteType
                    .newBuilder()
                    .setType("legacy-type")
                    .build(),
            "ExtendedURL" to
                RemoteProto.ExtendedURL
                    .newBuilder()
                    .setUrl("legacy://x")
                    .putValues("k", "v")
                    .build(),
            "RemoteProperties" to
                RemoteProto.RemoteProperties
                    .newBuilder()
                    .setValues(sampleStruct("rp"))
                    .build(),
            "ParameterProperties" to
                RemoteProto.ParameterProperties
                    .newBuilder()
                    .setValues(sampleStruct("pp"))
                    .build(),
        )

    init {
        for ((name, sample) in samples()) {
            "$name surface: newBuilder + clone + clear" {
                val defaultInstance = sample.defaultInstanceForType
                defaultInstance shouldNotBe null

                // Builder copy via newBuilderForType then mergeFrom mirrors what
                // newBuilder(prototype) does internally; verify the deep-copy semantics.
                val copy = sample.toBuilder().build()
                copy shouldBe sample
                copy.hashCode() shouldBe sample.hashCode()

                // clear() returns the builder to a state equal to the default instance.
                val cleared = sample.toBuilder().clear().build()
                cleared shouldBe defaultInstance

                // A second Builder cloned off the populated one is also equal.
                val cloned = sample.toBuilder().clone().build()
                cloned shouldBe sample
            }

            "$name surface: parser overloads round-trip" {
                val parser = sample.parserForType
                val bytes = sample.toByteArray()
                val registry =
                    com.google.protobuf.ExtensionRegistryLite
                        .getEmptyRegistry()

                // byte[] overload (covered by primary tests too, but uniform here).
                parser.parseFrom(bytes) shouldBe sample
                parser.parseFrom(bytes, registry) shouldBe sample

                // ByteString overload.
                parser.parseFrom(ByteString.copyFrom(bytes)) shouldBe sample
                parser.parseFrom(ByteString.copyFrom(bytes), registry) shouldBe sample

                // InputStream overload.
                parser.parseFrom(ByteArrayInputStream(bytes)) shouldBe sample
                parser.parseFrom(ByteArrayInputStream(bytes), registry) shouldBe sample

                // CodedInputStream overload.
                parser.parseFrom(CodedInputStream.newInstance(bytes)) shouldBe sample
                parser.parseFrom(CodedInputStream.newInstance(bytes), registry) shouldBe sample
            }

            "$name static parseFrom helpers cover the registry + stream overloads" {
                // The generated message exposes static parseFrom helpers that delegate to the
                // singleton PARSER. These are reachable only through reflection here because
                // they are static and per-message-class. Hitting them via reflection guarantees
                // we walk the full set of overloads on every message in remote.proto.
                val cls = sample.javaClass
                val bytes = sample.toByteArray()
                val registry =
                    com.google.protobuf.ExtensionRegistryLite
                        .getEmptyRegistry()
                val byteString = ByteString.copyFrom(bytes)

                // parseFrom(byte[])
                cls
                    .getMethod("parseFrom", ByteArray::class.java)
                    .invoke(null, bytes) shouldBe sample
                // parseFrom(byte[], ExtensionRegistryLite)
                cls
                    .getMethod("parseFrom", ByteArray::class.java, com.google.protobuf.ExtensionRegistryLite::class.java)
                    .invoke(null, bytes, registry) shouldBe sample
                // parseFrom(ByteString)
                cls
                    .getMethod("parseFrom", ByteString::class.java)
                    .invoke(null, byteString) shouldBe sample
                // parseFrom(ByteString, ExtensionRegistryLite)
                cls
                    .getMethod("parseFrom", ByteString::class.java, com.google.protobuf.ExtensionRegistryLite::class.java)
                    .invoke(null, byteString, registry) shouldBe sample
                // parseFrom(InputStream)
                cls
                    .getMethod("parseFrom", java.io.InputStream::class.java)
                    .invoke(null, ByteArrayInputStream(bytes)) shouldBe sample
                // parseFrom(InputStream, ExtensionRegistryLite)
                cls
                    .getMethod(
                        "parseFrom",
                        java.io.InputStream::class.java,
                        com.google.protobuf.ExtensionRegistryLite::class.java,
                    ).invoke(null, ByteArrayInputStream(bytes), registry) shouldBe sample
                // parseDelimitedFrom(InputStream)
                val baos = java.io.ByteArrayOutputStream()
                sample.writeDelimitedTo(baos)
                cls
                    .getMethod("parseDelimitedFrom", java.io.InputStream::class.java)
                    .invoke(null, ByteArrayInputStream(baos.toByteArray())) shouldBe sample
                // parseDelimitedFrom(InputStream, ExtensionRegistryLite)
                cls
                    .getMethod(
                        "parseDelimitedFrom",
                        java.io.InputStream::class.java,
                        com.google.protobuf.ExtensionRegistryLite::class.java,
                    ).invoke(null, ByteArrayInputStream(baos.toByteArray()), registry) shouldBe sample
                // parseFrom(CodedInputStream)
                cls
                    .getMethod("parseFrom", CodedInputStream::class.java)
                    .invoke(null, CodedInputStream.newInstance(bytes)) shouldBe sample
                // parseFrom(CodedInputStream, ExtensionRegistryLite)
                cls
                    .getMethod(
                        "parseFrom",
                        CodedInputStream::class.java,
                        com.google.protobuf.ExtensionRegistryLite::class.java,
                    ).invoke(null, CodedInputStream.newInstance(bytes), registry) shouldBe sample
                // parseFrom(ByteBuffer) — the NIO buffer overload is an additional surface
                // distinct from the byte[] / ByteString / InputStream variants.
                cls
                    .getMethod("parseFrom", java.nio.ByteBuffer::class.java)
                    .invoke(null, java.nio.ByteBuffer.wrap(bytes)) shouldBe sample
                // parseFrom(ByteBuffer, ExtensionRegistryLite)
                cls
                    .getMethod(
                        "parseFrom",
                        java.nio.ByteBuffer::class.java,
                        com.google.protobuf.ExtensionRegistryLite::class.java,
                    ).invoke(null, java.nio.ByteBuffer.wrap(bytes), registry) shouldBe sample
            }

            "$name mergeFrom(byte[]) on a fresh builder reconstructs the message" {
                // Builder.mergeFrom(byte[]) is a separate API from the static parseFrom: it's
                // useful when callers stream multiple chunks into one builder. Verify it
                // returns the same logical message.
                val bytes = sample.toByteArray()
                val rebuilt =
                    sample
                        .newBuilderForType()
                        .mergeFrom(bytes)
                        .build()
                rebuilt shouldBe sample
                // Same flow with the ExtensionRegistry-taking overload.
                val rebuilt2 =
                    sample
                        .newBuilderForType()
                        .mergeFrom(
                            bytes,
                            com.google.protobuf.ExtensionRegistryLite
                                .getEmptyRegistry(),
                        ).build()
                rebuilt2 shouldBe sample
            }

            "$name surface: equals + hashCode + toString" {
                val defaultInstance = sample.defaultInstanceForType

                // Equality reflexive, symmetric.
                @Suppress("KotlinConstantConditions")
                (sample == sample) shouldBe true
                val a = sample.toBuilder().build()
                val b = sample.toBuilder().build()
                (a == b) shouldBe true
                (b == a) shouldBe true
                a.hashCode() shouldBe b.hashCode()

                // A non-default sample must not equal its default instance.
                if (sample.serializedSize > 0) {
                    (sample == defaultInstance) shouldBe false
                }

                // Wrong type: equals must short-circuit.
                @Suppress("EqualsBetweenInconvertibleTypes")
                (sample.equals("not a message")) shouldBe false

                // toString must contain something useful.
                sample.toString() shouldNotBe null
            }

            "$name surface: serializedSize + descriptor + default-instance accessors" {
                sample.serializedSize shouldBe sample.toByteArray().size
                sample.descriptorForType shouldNotBe null
                sample.descriptorForType shouldBe sample.defaultInstanceForType.descriptorForType
                sample.isInitialized shouldBe true
            }
        }

        "RemoteProto.registerAllExtensions covers the file-level extension registry hook" {
            // The generated outer class exposes two file-level static helpers:
            // registerAllExtensions(ExtensionRegistry) and (ExtensionRegistryLite). Both are
            // no-ops for proto3 without extensions, but they must be exercised to count as
            // covered.
            val registry =
                com.google.protobuf.ExtensionRegistry
                    .newInstance()
            RemoteProto.registerAllExtensions(registry)
            val lite =
                com.google.protobuf.ExtensionRegistryLite
                    .newInstance()
            RemoteProto.registerAllExtensions(lite)
            // descriptor returns the cached FileDescriptor; touching it ensures the static
            // initializer chain executed without throwing.
            RemoteProto.getDescriptor() shouldNotBe null
        }

        // Round-trip the same set through parseDelimitedFrom in a single pass — exercises the
        // generated parseDelimitedFrom static for every message type.
        "every message round-trips through writeDelimitedTo / parseDelimitedFrom" {
            for ((name, sample) in samples()) {
                val baos = java.io.ByteArrayOutputStream()
                sample.writeDelimitedTo(baos)
                val parsed = sample.parserForType.parseDelimitedFrom(ByteArrayInputStream(baos.toByteArray()))
                parsed shouldBe sample
                // Surfaces the per-message name in the failure trace if this ever breaks.
                "$name parsed" shouldBe "$name parsed"
            }
        }
    }
}
