/*
 * Copyright The Dit Project Contributors
 */
package dev.dit.plugin.remote

import com.google.protobuf.ByteString
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

/**
 * Edge paths in the generated Builder/Message classes that the broad round-trip and
 * sweep tests miss. Each test deliberately exercises a code path the proto generator
 * produces but typical client code rarely hits:
 *
 *   - setXxxBytes(ByteString) string setters
 *   - getXxxBytes() called on a freshly parsed message (ByteString-backed field)
 *   - getXxx() called on a freshly parsed message (also ByteString-backed)
 *   - mergeFrom(Message) dispatch with both matching and non-matching concrete types
 *   - equals() returning false on differing field values (vs. existing equality tests
 *     which only assert true)
 *   - newBuilder(prototype) static
 *   - Builder.buildPartial() vs Builder.build()
 *   - getXxxOrBuilderList for repeated message fields
 *   - Message.getSerializedSize() caching path (called twice)
 *   - Map field putAll + getXxxMap + getXxxCount + clearXxx + removeXxx
 */
class RemoteProtoEdgePathsTest : StringSpec() {
    private fun sampleStruct(): Struct =
        Struct
            .newBuilder()
            .putFields("k", Value.newBuilder().setStringValue("v").build())
            .build()

    init {
        "setUrlBytes covers the ByteString string-setter on every string-bearing message" {
            val bs = ByteString.copyFromUtf8("via-bytes")
            // Every message that has a `string` field accepts the *Bytes overload.
            RemoteProto.GetTypeResponse
                .newBuilder()
                .setTypeBytes(bs)
                .build()
                .type shouldBe "via-bytes"
            RemoteProto.RemoteType
                .newBuilder()
                .setTypeBytes(bs)
                .build()
                .type shouldBe "via-bytes"
            RemoteProto.FromURLRequest
                .newBuilder()
                .setUrlBytes(bs)
                .build()
                .url shouldBe "via-bytes"
            RemoteProto.ToURLResponse
                .newBuilder()
                .setUrlBytes(bs)
                .build()
                .url shouldBe "via-bytes"
            RemoteProto.ExtendedURL
                .newBuilder()
                .setUrlBytes(bs)
                .build()
                .url shouldBe "via-bytes"
            RemoteProto.Commit
                .newBuilder()
                .setIdBytes(bs)
                .build()
                .id shouldBe "via-bytes"
            RemoteProto.GetCommitRequest
                .newBuilder()
                .setCommitIdBytes(bs)
                .build()
                .commitId shouldBe "via-bytes"
            RemoteProto.Tag
                .newBuilder()
                .setKeyBytes(bs)
                .build()
                .key shouldBe "via-bytes"
            RemoteProto.Tag
                .newBuilder()
                .setKey("k")
                .setValueStringBytes(bs)
                .build()
                .valueString shouldBe "via-bytes"
        }

        "getXxx + getXxxBytes after parseFrom hits the ByteString-backed-field branch" {
            // After parseFrom, the field is initialized from the wire as a ByteString rather
            // than a String. The first getXxx() call then walks the bs.toStringUtf8() branch
            // that String-set fields never reach.
            val msg =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u1")
                    .build()
            val parsed = RemoteProto.FromURLRequest.parseFrom(msg.toByteArray())
            parsed.url shouldBe "u1"
            // Calling getUrlBytes after getUrl re-uses the cached String -> bytes path too.
            parsed.urlBytes.toStringUtf8() shouldBe "u1"

            // Reverse order: ask for Bytes first, then for the String. This exercises the
            // alternate caching branch in getUrl/getUrlBytes.
            val parsed2 = RemoteProto.FromURLRequest.parseFrom(msg.toByteArray())
            parsed2.urlBytes.toStringUtf8() shouldBe "u1"
            parsed2.url shouldBe "u1"
        }

        "mergeFrom(Message) dispatches via instanceof for matching type" {
            val a =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("a")
                    .build()
            val b =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("b")
                    .build()
            // Pass the message in via the Message-typed overload, not the concrete one.
            val merged = a.toBuilder().mergeFrom(b as com.google.protobuf.Message).build()
            merged.type shouldBe "b"
        }

        "mergeFrom(Message) rejects a non-matching concrete type" {
            // The instanceof check fails when the other side is a different message type;
            // the super.mergeFrom path raises an IllegalArgumentException because the wire
            // format would be ambiguous. Asserting that branch fires keeps the test honest.
            val a =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("a")
                    .build()
            val other: com.google.protobuf.Message = RemoteProto.RemoteType.getDefaultInstance()
            io.kotlintest.shouldThrow<IllegalArgumentException> {
                a.toBuilder().mergeFrom(other)
            }
        }

        "equals returns false when scalar fields differ" {
            val a =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("a")
                    .build()
            val b =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("b")
                    .build()
            (a == b) shouldBe false
            (a == RemoteProto.GetTypeResponse.getDefaultInstance()) shouldBe false
        }

        "equals returns false when map fields differ" {
            val a =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u")
                    .putProperties("k", "1")
                    .build()
            val b =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u")
                    .putProperties("k", "2")
                    .build()
            (a == b) shouldBe false
        }

        "equals returns false when oneof case differs on Tag" {
            val nullVariant =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueNull(true)
                    .build()
            val stringVariant =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueString("v")
                    .build()
            (nullVariant == stringVariant) shouldBe false
        }

        "equals returns false when oneof case differs on GetCommitResponse" {
            val nullVariant =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitNull(true)
                    .build()
            val valueVariant =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitValue(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("c")
                            .build(),
                    ).build()
            (nullVariant == valueVariant) shouldBe false
        }

        "equals returns false when repeated fields differ" {
            val a =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("a")
                            .build(),
                    ).build()
            val b =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("b")
                            .build(),
                    ).build()
            (a == b) shouldBe false
        }

        "static newBuilder(prototype) is equivalent to toBuilder().build()" {
            val original =
                RemoteProto.Commit
                    .newBuilder()
                    .setId("x")
                    .setProperties(sampleStruct())
                    .build()
            // Walk the per-type newBuilder(prototype) static for several messages.
            RemoteProto.Commit.newBuilder(original).build() shouldBe original
            RemoteProto.GetTypeRequest
                .newBuilder(RemoteProto.GetTypeRequest.getDefaultInstance())
                .build() shouldBe RemoteProto.GetTypeRequest.getDefaultInstance()
            val tag =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueString("v")
                    .build()
            RemoteProto.Tag.newBuilder(tag).build() shouldBe tag
        }

        "buildPartial returns the under-construction message without isInitialized validation" {
            // For proto3 messages every required field is implicit, so buildPartial and
            // build are equivalent. The buildPartial path is still a distinct method.
            val b = RemoteProto.Commit.newBuilder().setId("partial")
            val partial = b.buildPartial()
            partial.id shouldBe "partial"
            val full = b.build()
            full shouldBe partial
        }

        "getSerializedSize is stable across repeated calls and matches toByteArray" {
            // The generated message caches the serialized size on first call; touching it
            // twice exercises the cached branch.
            val msg =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .setRemote(sampleStruct())
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k")
                            .setValueString("v")
                            .build(),
                    ).build()
            val first = msg.serializedSize
            val second = msg.serializedSize
            first shouldBe second
            first shouldBe msg.toByteArray().size
        }

        "writeTo round-trips through CodedOutputStream" {
            val msg =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("via-cos")
                    .build()
            val baos = java.io.ByteArrayOutputStream()
            val cos =
                com.google.protobuf.CodedOutputStream
                    .newInstance(baos)
            msg.writeTo(cos)
            cos.flush()
            RemoteProto.GetTypeResponse.parseFrom(baos.toByteArray()) shouldBe msg
        }

        "map field putAll + removeXxx + containsXxx + getXxxOrDefault on FromURLRequest" {
            val req =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u")
                    .putAllProperties(mapOf("a" to "1", "b" to "2", "c" to "3"))
                    .removeProperties("b")
                    .build()
            req.propertiesCount shouldBe 2
            req.containsProperties("a") shouldBe true
            req.containsProperties("b") shouldBe false
            req.getPropertiesOrDefault("missing", "fallback") shouldBe "fallback"
            req.getPropertiesOrThrow("a") shouldBe "1"
            // putProperties on an existing builder is the simplest distinct mutation path.
            val b2 = req.toBuilder().putProperties("d", "4").build()
            b2.propertiesCount shouldBe 3
            b2.propertiesMap["d"] shouldBe "4"
            // clearProperties is a separate code path.
            req
                .toBuilder()
                .clearProperties()
                .build()
                .propertiesCount shouldBe 0
        }

        "map field removeXxx + clearXxx on ToURLResponse and ExtendedURL" {
            val res =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("u")
                    .putAllProperties(mapOf("a" to "1", "b" to "2"))
                    .removeProperties("a")
                    .build()
            res.propertiesMap.keys shouldBe setOf("b")
            res
                .toBuilder()
                .clearProperties()
                .build()
                .propertiesCount shouldBe 0

            val ext =
                RemoteProto.ExtendedURL
                    .newBuilder()
                    .setUrl("u")
                    .putAllValues(mapOf("a" to "1", "b" to "2"))
                    .removeValues("a")
                    .build()
            ext.valuesMap.keys shouldBe setOf("b")
            ext
                .toBuilder()
                .clearValues()
                .build()
                .valuesCount shouldBe 0
        }

        "nested message Builder integration: getXxxBuilder / hasXxx after build" {
            // GetCommitRequest has three nested-message-or-scalar fields. The fluent Builder
            // exposes getRemoteBuilder() that lets callers mutate the nested message in place,
            // which is a distinct path from .setRemote(struct).
            val builder = RemoteProto.GetCommitRequest.newBuilder()
            builder.hasRemote() shouldBe false
            builder.remoteBuilder.putFields("k", Value.newBuilder().setStringValue("v").build())
            builder.hasRemote() shouldBe true
            val msg = builder.build()
            msg.remote.getFieldsOrThrow("k").stringValue shouldBe "v"
            // Same for parameters.
            val b2 = msg.toBuilder()
            b2.parametersBuilder.putFields("x", Value.newBuilder().setNumberValue(1.0).build())
            val msg2 = b2.build()
            msg2.parameters.getFieldsOrThrow("x").numberValue shouldBe 1.0
            // mergeRemote with the message-overload merges field by field.
            val merged =
                msg
                    .toBuilder()
                    .mergeRemote(
                        Struct.newBuilder().putFields("k2", Value.newBuilder().setStringValue("v2").build()).build(),
                    ).build()
            merged.remote.fieldsCount shouldBe 2
            // clearRemote / clearParameters are distinct methods.
            msg
                .toBuilder()
                .clearRemote()
                .build()
                .hasRemote() shouldBe false
            msg
                .toBuilder()
                .clearParameters()
                .build()
                .hasParameters() shouldBe false
        }

        "Tag clearKey + clearValue cover individual field clears" {
            val tag =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueString("v")
                    .build()
            tag
                .toBuilder()
                .clearKey()
                .build()
                .key shouldBe ""
            tag
                .toBuilder()
                .clearValue()
                .build()
                .valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_NOT_SET
        }

        "Commit.Builder hasProperties + mergeProperties + clearProperties cycle" {
            val c =
                RemoteProto.Commit
                    .newBuilder()
                    .setId("x")
                    .setProperties(sampleStruct())
                    .build()
            c.hasProperties() shouldBe true
            // mergeProperties on an empty target hits the merge-with-empty branch.
            val merged =
                RemoteProto.Commit
                    .newBuilder()
                    .setId("y")
                    .mergeProperties(sampleStruct())
                    .build()
            merged.hasProperties() shouldBe true
            // clearId / clearProperties are individual field clears.
            c
                .toBuilder()
                .clearId()
                .build()
                .id shouldBe ""
            c
                .toBuilder()
                .clearProperties()
                .build()
                .hasProperties() shouldBe false
        }

        "GetCommitResponse oneof: setCommitValue(Builder) and mergeCommitValue paths" {
            val builder =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitValue(RemoteProto.Commit.newBuilder().setId("first"))
            builder.commitValue.id shouldBe "first"
            // mergeCommitValue on an existing commit value merges the inner message.
            val merged =
                builder
                    .mergeCommitValue(
                        RemoteProto.Commit
                            .newBuilder()
                            .setProperties(sampleStruct())
                            .build(),
                    ).build()
            merged.commitValue.id shouldBe "first"
            merged.commitValue.hasProperties() shouldBe true
            // clearCommitValue + clearCommitNull individually drop the oneof.
            val viaClearValue =
                builder
                    .clone()
                    .clearCommitValue()
                    .build()
            viaClearValue.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_NOT_SET
            val viaClearNull =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitNull(true)
                    .clearCommitNull()
                    .build()
            viaClearNull.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_NOT_SET
        }

        "ListCommitRequest.Builder ensure-tags + addTags(int, ...) overloads" {
            val builder =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("first")
                            .setValueString("v")
                            .build(),
                    ).addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("second")
                            .setValueString("v")
                            .build(),
                    )
            // addTags(int, message) insert.
            builder.addTags(
                0,
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("zeroth")
                    .setValueString("v")
                    .build(),
            )
            builder.tagsCount shouldBe 3
            builder.getTags(0).key shouldBe "zeroth"
            // addTags(int, Builder) insert.
            builder.addTags(
                1,
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("between")
                    .setValueString("v"),
            )
            builder.tagsCount shouldBe 4
            builder.getTags(1).key shouldBe "between"
            // tagsBuilderList and getTagsBuilder(int) expose the internal builder list.
            builder.tagsBuilderList.size shouldBe 4
            builder.getTagsBuilder(0).key shouldBe "zeroth"
            // addTagsBuilder() + addTagsBuilder(int) create new builders in place.
            builder.addTagsBuilder().setKey("appended").setValueString("v")
            builder.addTagsBuilder(0).setKey("prepended").setValueString("v")
            builder.tagsCount shouldBe 6
            builder.getTagsOrBuilder(0).key shouldBe "prepended"
            builder.tagsOrBuilderList.size shouldBe 6
            // removeTags removes by index.
            builder.removeTags(0)
            builder.tagsCount shouldBe 5
        }

        "ListCommitResponse.Builder commits accessor surface" {
            val builder =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("a")
                            .build(),
                    ).addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("b")
                            .build(),
                    )
            builder.addCommits(
                0,
                RemoteProto.Commit
                    .newBuilder()
                    .setId("zero")
                    .build(),
            )
            builder.addCommits(1, RemoteProto.Commit.newBuilder().setId("between"))
            builder.commitsCount shouldBe 4
            builder.commitsBuilderList.size shouldBe 4
            builder.getCommitsBuilder(0).id shouldBe "zero"
            builder.addCommitsBuilder().setId("appended")
            builder.addCommitsBuilder(0).setId("prepended")
            builder.commitsCount shouldBe 6
            builder.removeCommits(0)
            builder.commitsCount shouldBe 5
            builder.tagsViaCommits() shouldBe Unit
        }

        "parseFrom + getSerializedSize + isInitialized after parse" {
            val msg =
                RemoteProto.GetCommitRequest
                    .newBuilder()
                    .setCommitId("x")
                    .build()
            val parsed = RemoteProto.GetCommitRequest.parseFrom(msg.toByteArray())
            parsed.isInitialized shouldBe true
            parsed.serializedSize shouldBe msg.serializedSize
        }

        "every message-or-builder list exposes correct view" {
            val req =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k")
                            .setValueString("v")
                            .build(),
                    ).build()
            req.tagsOrBuilderList.size shouldBe 1
            req.tagsList.size shouldBe 1
            req.getTagsOrBuilder(0) shouldNotBe null
        }

        // Each Builder class declares its own public static `getDescriptor()`. The Message-side
        // getDescriptor() is covered by the outer-class touch in the sweep test, but the
        // Builder-side static is only reachable via reflection from Kotlin (the Builder is a
        // member class with a private constructor on the message side).
        "every Builder.getDescriptor static returns a non-null descriptor" {
            val outer = RemoteProto::class.java
            val nestedNames =
                listOf(
                    "GetTypeRequest",
                    "GetTypeResponse",
                    "FromURLRequest",
                    "FromURLResponse",
                    "ToURLRequest",
                    "ToURLResponse",
                    "GetParametersRequest",
                    "GetParametersResponse",
                    "ValidateRemoteRequest",
                    "ValidateRemoteResponse",
                    "ValidateParametersRequest",
                    "ValidateParametersResponse",
                    "Tag",
                    "Commit",
                    "GetCommitRequest",
                    "GetCommitResponse",
                    "ListCommitRequest",
                    "ListCommitResponse",
                    "RemoteType",
                    "ExtendedURL",
                    "RemoteProperties",
                    "ParameterProperties",
                )
            for (name in nestedNames) {
                val msgCls = Class.forName("${outer.name}\$$name")
                val builderCls = Class.forName("${outer.name}\$$name\$Builder")
                builderCls.getMethod("getDescriptor").invoke(null) shouldNotBe null
                msgCls.getMethod("getDescriptor").invoke(null) shouldNotBe null
            }
        }

        "equals(non-Message) short-circuits via the super-call branch" {
            // The generated equals() short-circuits via super.equals(obj) when the argument
            // is not a same-typed message. Cover that branch on a few representative messages.
            @Suppress("EqualsBetweenInconvertibleTypes")
            (RemoteProto.GetTypeRequest.getDefaultInstance().equals(Any())) shouldBe false
            @Suppress("EqualsBetweenInconvertibleTypes")
            (
                RemoteProto.RemoteType
                    .newBuilder()
                    .setType("x")
                    .build()
                    .equals(Any())
            ) shouldBe false
            @Suppress("EqualsBetweenInconvertibleTypes")
            (
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .build()
                    .equals(42)
            ) shouldBe false
        }

        "equals(self) takes the identity short-circuit" {
            val m =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("self")
                    .build()
            @Suppress("KotlinConstantConditions")
            (m == m) shouldBe true
            // hashCode caches on second call; both must return the same value.
            val h1 = m.hashCode()
            val h2 = m.hashCode()
            h1 shouldBe h2
        }

        "mergeFrom(CodedInputStream) tolerates unknown fields preserved through round-trip" {
            // Build a wire payload that includes a known tag plus an unknown tag (field
            // number 999 is not declared on GetTypeResponse). The generated mergeFrom must
            // route the unknown bytes through parseUnknownField rather than throwing.
            val baos = java.io.ByteArrayOutputStream()
            val out =
                com.google.protobuf.CodedOutputStream
                    .newInstance(baos)
            out.writeString(1, "x")
            out.writeInt64(999, 42L)
            out.flush()
            val parsed = RemoteProto.GetTypeResponse.parseFrom(baos.toByteArray())
            parsed.type shouldBe "x"
            parsed.unknownFields shouldNotBe null
            parsed.unknownFields.serializedSize shouldNotBe 0
        }

        "freshly parsed message getXxx covers the ByteString-backed branch on every string field" {
            // After parseFrom, the field is initialized from the wire as a ByteString rather
            // than a String. The first getXxx() call walks the bs.toStringUtf8() branch
            // that String-set fields never reach. Exercise this for every string-bearing
            // message in the schema.
            data class Probe(
                val build: () -> com.google.protobuf.Message,
                val read: (com.google.protobuf.Message) -> String,
            )

            val probes =
                listOf(
                    Probe({
                        RemoteProto.GetTypeResponse
                            .newBuilder()
                            .setType("v")
                            .build()
                    }) {
                        (it as RemoteProto.GetTypeResponse).type
                    },
                    Probe({
                        RemoteProto.RemoteType
                            .newBuilder()
                            .setType("v")
                            .build()
                    }) {
                        (it as RemoteProto.RemoteType).type
                    },
                    Probe({
                        RemoteProto.FromURLRequest
                            .newBuilder()
                            .setUrl("v")
                            .build()
                    }) {
                        (it as RemoteProto.FromURLRequest).url
                    },
                    Probe({
                        RemoteProto.ToURLResponse
                            .newBuilder()
                            .setUrl("v")
                            .build()
                    }) {
                        (it as RemoteProto.ToURLResponse).url
                    },
                    Probe({
                        RemoteProto.ExtendedURL
                            .newBuilder()
                            .setUrl("v")
                            .build()
                    }) {
                        (it as RemoteProto.ExtendedURL).url
                    },
                    Probe({
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("v")
                            .build()
                    }) {
                        (it as RemoteProto.Commit).id
                    },
                    Probe({
                        RemoteProto.GetCommitRequest
                            .newBuilder()
                            .setCommitId("v")
                            .build()
                    }) {
                        (it as RemoteProto.GetCommitRequest).commitId
                    },
                    Probe({
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("v")
                            .build()
                    }) {
                        (it as RemoteProto.Tag).key
                    },
                    Probe({
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k")
                            .setValueString("v")
                            .build()
                    }) {
                        (it as RemoteProto.Tag).valueString
                    },
                )
            for (probe in probes) {
                val original = probe.build()
                val parsed = original.parserForType.parseFrom(original.toByteArray())
                probe.read(parsed) shouldBe "v"
            }
        }

        "freshly parsed message getXxxBytes covers the ByteString-backed branch on every string field" {
            val probes =
                listOf<Pair<() -> com.google.protobuf.Message, (com.google.protobuf.Message) -> com.google.protobuf.ByteString>>(
                    {
                        RemoteProto.GetTypeResponse
                            .newBuilder()
                            .setType("v")
                            .build()
                    } to {
                        (it as RemoteProto.GetTypeResponse).typeBytes
                    },
                    {
                        RemoteProto.RemoteType
                            .newBuilder()
                            .setType("v")
                            .build()
                    } to {
                        (it as RemoteProto.RemoteType).typeBytes
                    },
                    {
                        RemoteProto.FromURLRequest
                            .newBuilder()
                            .setUrl("v")
                            .build()
                    } to {
                        (it as RemoteProto.FromURLRequest).urlBytes
                    },
                    {
                        RemoteProto.ToURLResponse
                            .newBuilder()
                            .setUrl("v")
                            .build()
                    } to {
                        (it as RemoteProto.ToURLResponse).urlBytes
                    },
                    {
                        RemoteProto.ExtendedURL
                            .newBuilder()
                            .setUrl("v")
                            .build()
                    } to {
                        (it as RemoteProto.ExtendedURL).urlBytes
                    },
                    {
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("v")
                            .build()
                    } to {
                        (it as RemoteProto.Commit).idBytes
                    },
                    {
                        RemoteProto.GetCommitRequest
                            .newBuilder()
                            .setCommitId("v")
                            .build()
                    } to {
                        (it as RemoteProto.GetCommitRequest).commitIdBytes
                    },
                    {
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("v")
                            .build()
                    } to {
                        (it as RemoteProto.Tag).keyBytes
                    },
                    {
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k")
                            .setValueString("v")
                            .build()
                    } to {
                        (it as RemoteProto.Tag).valueStringBytes
                    },
                )
            for ((build, read) in probes) {
                val original = build()
                val parsed = original.parserForType.parseFrom(original.toByteArray())
                read(parsed).toStringUtf8() shouldBe "v"
            }
        }

        "Builder.getXxxBytes returns the bytes form on a String-set builder for every message" {
            val s = "via-string"
            RemoteProto.GetTypeResponse
                .newBuilder()
                .setType(s)
                .typeBytes
                .toStringUtf8() shouldBe s
            RemoteProto.RemoteType
                .newBuilder()
                .setType(s)
                .typeBytes
                .toStringUtf8() shouldBe s
            RemoteProto.FromURLRequest
                .newBuilder()
                .setUrl(s)
                .urlBytes
                .toStringUtf8() shouldBe s
            RemoteProto.ToURLResponse
                .newBuilder()
                .setUrl(s)
                .urlBytes
                .toStringUtf8() shouldBe s
            RemoteProto.ExtendedURL
                .newBuilder()
                .setUrl(s)
                .urlBytes
                .toStringUtf8() shouldBe s
            RemoteProto.Commit
                .newBuilder()
                .setId(s)
                .idBytes
                .toStringUtf8() shouldBe s
            RemoteProto.GetCommitRequest
                .newBuilder()
                .setCommitId(s)
                .commitIdBytes
                .toStringUtf8() shouldBe s
            RemoteProto.Tag
                .newBuilder()
                .setKey(s)
                .keyBytes
                .toStringUtf8() shouldBe s
            RemoteProto.Tag
                .newBuilder()
                .setKey("k")
                .setValueString(s)
                .valueStringBytes
                .toStringUtf8() shouldBe s
        }

        "Builder.getXxx returns the String form on a Bytes-set builder for every message" {
            val bs =
                com.google.protobuf.ByteString
                    .copyFromUtf8("via-bytes")
            RemoteProto.GetTypeResponse
                .newBuilder()
                .setTypeBytes(bs)
                .type shouldBe "via-bytes"
            RemoteProto.RemoteType
                .newBuilder()
                .setTypeBytes(bs)
                .type shouldBe "via-bytes"
            RemoteProto.FromURLRequest
                .newBuilder()
                .setUrlBytes(bs)
                .url shouldBe "via-bytes"
            RemoteProto.ToURLResponse
                .newBuilder()
                .setUrlBytes(bs)
                .url shouldBe "via-bytes"
            RemoteProto.ExtendedURL
                .newBuilder()
                .setUrlBytes(bs)
                .url shouldBe "via-bytes"
            RemoteProto.Commit
                .newBuilder()
                .setIdBytes(bs)
                .id shouldBe "via-bytes"
            RemoteProto.GetCommitRequest
                .newBuilder()
                .setCommitIdBytes(bs)
                .commitId shouldBe "via-bytes"
            RemoteProto.Tag
                .newBuilder()
                .setKeyBytes(bs)
                .key shouldBe "via-bytes"
            RemoteProto.Tag
                .newBuilder()
                .setKey("k")
                .setValueStringBytes(bs)
                .valueString shouldBe "via-bytes"
        }

        "nested-message field-builder paths fire when getXxxBuilder allocates the SingleFieldBuilder" {
            // For every message with a `.google.protobuf.Struct` field, calling getXxxBuilder
            // allocates a SingleFieldBuilder. Subsequent setRemote / mergeRemote / clearRemote
            // calls then walk the `*Builder_ != null` branch which is otherwise unreachable
            // from typical client code.
            fun exerciseStructField(
                fresh: () -> com.google.protobuf.Message.Builder,
                allocate: (com.google.protobuf.Message.Builder) -> Unit,
                setStruct: (com.google.protobuf.Message.Builder, Struct) -> Unit,
                mergeStruct: (com.google.protobuf.Message.Builder, Struct) -> Unit,
                clear: (com.google.protobuf.Message.Builder) -> Unit,
                getOrBuilder: (com.google.protobuf.Message.Builder) -> Any,
            ) {
                // (a) allocate the field builder via getXxxBuilder, then set.
                val b1 = fresh()
                allocate(b1)
                setStruct(b1, sampleStruct())
                getOrBuilder(b1) shouldNotBe null

                // (b) allocate the field builder then merge.
                val b2 = fresh()
                allocate(b2)
                mergeStruct(b2, sampleStruct())
                getOrBuilder(b2) shouldNotBe null

                // (c) allocate the field builder then clear.
                val b3 = fresh()
                allocate(b3)
                setStruct(b3, sampleStruct())
                clear(b3)
                getOrBuilder(b3) shouldNotBe null

                // (d) Set then merge with existing data — exercises the recursive
                // getRemoteBuilder().mergeFrom branch in mergeXxx.
                val b4 = fresh()
                setStruct(b4, sampleStruct())
                mergeStruct(b4, Struct.newBuilder().putFields("extra", Value.newBuilder().setStringValue("e").build()).build())
                getOrBuilder(b4) shouldNotBe null

                // (e) setXxx(Builder) overload covers the message-builder-typed setter.
                val b5 = fresh()
                setStructViaBuilderTypedOverload(b5)
            }

            exerciseStructField(
                { RemoteProto.FromURLResponse.newBuilder() },
                { (it as RemoteProto.FromURLResponse.Builder).remoteBuilder },
                { b, s -> (b as RemoteProto.FromURLResponse.Builder).setRemote(s) },
                { b, s -> (b as RemoteProto.FromURLResponse.Builder).mergeRemote(s) },
                { (it as RemoteProto.FromURLResponse.Builder).clearRemote() },
                { (it as RemoteProto.FromURLResponse.Builder).remoteOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.ToURLRequest.newBuilder() },
                { (it as RemoteProto.ToURLRequest.Builder).remoteBuilder },
                { b, s -> (b as RemoteProto.ToURLRequest.Builder).setRemote(s) },
                { b, s -> (b as RemoteProto.ToURLRequest.Builder).mergeRemote(s) },
                { (it as RemoteProto.ToURLRequest.Builder).clearRemote() },
                { (it as RemoteProto.ToURLRequest.Builder).remoteOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.GetParametersRequest.newBuilder() },
                { (it as RemoteProto.GetParametersRequest.Builder).remoteBuilder },
                { b, s -> (b as RemoteProto.GetParametersRequest.Builder).setRemote(s) },
                { b, s -> (b as RemoteProto.GetParametersRequest.Builder).mergeRemote(s) },
                { (it as RemoteProto.GetParametersRequest.Builder).clearRemote() },
                { (it as RemoteProto.GetParametersRequest.Builder).remoteOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.GetParametersResponse.newBuilder() },
                { (it as RemoteProto.GetParametersResponse.Builder).parametersBuilder },
                { b, s -> (b as RemoteProto.GetParametersResponse.Builder).setParameters(s) },
                { b, s -> (b as RemoteProto.GetParametersResponse.Builder).mergeParameters(s) },
                { (it as RemoteProto.GetParametersResponse.Builder).clearParameters() },
                { (it as RemoteProto.GetParametersResponse.Builder).parametersOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.ValidateRemoteRequest.newBuilder() },
                { (it as RemoteProto.ValidateRemoteRequest.Builder).remoteBuilder },
                { b, s -> (b as RemoteProto.ValidateRemoteRequest.Builder).setRemote(s) },
                { b, s -> (b as RemoteProto.ValidateRemoteRequest.Builder).mergeRemote(s) },
                { (it as RemoteProto.ValidateRemoteRequest.Builder).clearRemote() },
                { (it as RemoteProto.ValidateRemoteRequest.Builder).remoteOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.ValidateParametersRequest.newBuilder() },
                { (it as RemoteProto.ValidateParametersRequest.Builder).parametersBuilder },
                { b, s -> (b as RemoteProto.ValidateParametersRequest.Builder).setParameters(s) },
                { b, s -> (b as RemoteProto.ValidateParametersRequest.Builder).mergeParameters(s) },
                { (it as RemoteProto.ValidateParametersRequest.Builder).clearParameters() },
                { (it as RemoteProto.ValidateParametersRequest.Builder).parametersOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.Commit.newBuilder() },
                { (it as RemoteProto.Commit.Builder).propertiesBuilder },
                { b, s -> (b as RemoteProto.Commit.Builder).setProperties(s) },
                { b, s -> (b as RemoteProto.Commit.Builder).mergeProperties(s) },
                { (it as RemoteProto.Commit.Builder).clearProperties() },
                { (it as RemoteProto.Commit.Builder).propertiesOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.ListCommitRequest.newBuilder() },
                { (it as RemoteProto.ListCommitRequest.Builder).remoteBuilder },
                { b, s -> (b as RemoteProto.ListCommitRequest.Builder).setRemote(s) },
                { b, s -> (b as RemoteProto.ListCommitRequest.Builder).mergeRemote(s) },
                { (it as RemoteProto.ListCommitRequest.Builder).clearRemote() },
                { (it as RemoteProto.ListCommitRequest.Builder).remoteOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.ListCommitRequest.newBuilder() },
                { (it as RemoteProto.ListCommitRequest.Builder).parametersBuilder },
                { b, s -> (b as RemoteProto.ListCommitRequest.Builder).setParameters(s) },
                { b, s -> (b as RemoteProto.ListCommitRequest.Builder).mergeParameters(s) },
                { (it as RemoteProto.ListCommitRequest.Builder).clearParameters() },
                { (it as RemoteProto.ListCommitRequest.Builder).parametersOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.GetCommitRequest.newBuilder() },
                { (it as RemoteProto.GetCommitRequest.Builder).remoteBuilder },
                { b, s -> (b as RemoteProto.GetCommitRequest.Builder).setRemote(s) },
                { b, s -> (b as RemoteProto.GetCommitRequest.Builder).mergeRemote(s) },
                { (it as RemoteProto.GetCommitRequest.Builder).clearRemote() },
                { (it as RemoteProto.GetCommitRequest.Builder).remoteOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.GetCommitRequest.newBuilder() },
                { (it as RemoteProto.GetCommitRequest.Builder).parametersBuilder },
                { b, s -> (b as RemoteProto.GetCommitRequest.Builder).setParameters(s) },
                { b, s -> (b as RemoteProto.GetCommitRequest.Builder).mergeParameters(s) },
                { (it as RemoteProto.GetCommitRequest.Builder).clearParameters() },
                { (it as RemoteProto.GetCommitRequest.Builder).parametersOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.RemoteProperties.newBuilder() },
                { (it as RemoteProto.RemoteProperties.Builder).valuesBuilder },
                { b, s -> (b as RemoteProto.RemoteProperties.Builder).setValues(s) },
                { b, s -> (b as RemoteProto.RemoteProperties.Builder).mergeValues(s) },
                { (it as RemoteProto.RemoteProperties.Builder).clearValues() },
                { (it as RemoteProto.RemoteProperties.Builder).valuesOrBuilder },
            )
            exerciseStructField(
                { RemoteProto.ParameterProperties.newBuilder() },
                { (it as RemoteProto.ParameterProperties.Builder).valuesBuilder },
                { b, s -> (b as RemoteProto.ParameterProperties.Builder).setValues(s) },
                { b, s -> (b as RemoteProto.ParameterProperties.Builder).mergeValues(s) },
                { (it as RemoteProto.ParameterProperties.Builder).clearValues() },
                { (it as RemoteProto.ParameterProperties.Builder).valuesOrBuilder },
            )

            // GetCommitResponse.commit_value: nested Commit message inside a oneof.
            val builder = RemoteProto.GetCommitResponse.newBuilder()
            builder.commitValueBuilder.setId("via-builder")
            builder.commitValue.id shouldBe "via-builder"
            builder.mergeCommitValue(
                RemoteProto.Commit
                    .newBuilder()
                    .setProperties(sampleStruct())
                    .build(),
            )
            builder.commitValue.hasProperties() shouldBe true
        }

        "ListCommitRequest.Builder mergeFrom(other) merges nested Struct + repeated tags" {
            val source =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .setRemote(sampleStruct())
                    .setParameters(sampleStruct())
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k1")
                            .setValueString("v1")
                            .build(),
                    ).build()

            val intoEmpty =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .mergeFrom(source)
                    .build()
            intoEmpty.tagsCount shouldBe 1
            intoEmpty.hasRemote() shouldBe true

            val intoPopulated =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("existing")
                            .setValueString("v0")
                            .build(),
                    ).mergeFrom(source)
                    .build()
            intoPopulated.tagsCount shouldBe 2
            intoPopulated.getTags(0).key shouldBe "existing"
            intoPopulated.getTags(1).key shouldBe "k1"
        }

        "ListCommitResponse.Builder mergeFrom merges repeated commits in both empty and populated targets" {
            val source =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("a")
                            .build(),
                    ).build()

            val intoEmpty =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .mergeFrom(source)
                    .build()
            intoEmpty.commitsCount shouldBe 1

            val intoPopulated =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("existing")
                            .build(),
                    ).mergeFrom(source)
                    .build()
            intoPopulated.commitsCount shouldBe 2
        }

        "every Builder.getDescriptorForType + getDefaultInstanceForType returns sensible data" {
            val builders =
                listOf<com.google.protobuf.Message.Builder>(
                    RemoteProto.GetTypeRequest.newBuilder(),
                    RemoteProto.GetTypeResponse.newBuilder(),
                    RemoteProto.FromURLRequest.newBuilder(),
                    RemoteProto.FromURLResponse.newBuilder(),
                    RemoteProto.ToURLRequest.newBuilder(),
                    RemoteProto.ToURLResponse.newBuilder(),
                    RemoteProto.GetParametersRequest.newBuilder(),
                    RemoteProto.GetParametersResponse.newBuilder(),
                    RemoteProto.ValidateRemoteRequest.newBuilder(),
                    RemoteProto.ValidateRemoteResponse.newBuilder(),
                    RemoteProto.ValidateParametersRequest.newBuilder(),
                    RemoteProto.ValidateParametersResponse.newBuilder(),
                    RemoteProto.Tag.newBuilder(),
                    RemoteProto.Commit.newBuilder(),
                    RemoteProto.GetCommitRequest.newBuilder(),
                    RemoteProto.GetCommitResponse.newBuilder(),
                    RemoteProto.ListCommitRequest.newBuilder(),
                    RemoteProto.ListCommitResponse.newBuilder(),
                    RemoteProto.RemoteType.newBuilder(),
                    RemoteProto.ExtendedURL.newBuilder(),
                    RemoteProto.RemoteProperties.newBuilder(),
                    RemoteProto.ParameterProperties.newBuilder(),
                )
            for (b in builders) {
                b.descriptorForType shouldNotBe null
                b.defaultInstanceForType shouldNotBe null
                b.isInitialized shouldBe true
            }
        }

        "every Builder rejects mergeFrom from a foreign message type" {
            // The instanceof check fails for a foreign-type other, and the
            // super.mergeFrom path raises IllegalArgumentException. Walking it on every
            // builder covers the `super.mergeFrom(other)` line in each generated class.
            val foreign: com.google.protobuf.Message = RemoteProto.GetTypeRequest.getDefaultInstance()
            val builders =
                listOf<com.google.protobuf.Message.Builder>(
                    RemoteProto.GetTypeResponse.newBuilder().also { it.setType("a") },
                    RemoteProto.FromURLRequest.newBuilder().also { it.setUrl("a") },
                    RemoteProto.FromURLResponse.newBuilder().also { it.setRemote(sampleStruct()) },
                    RemoteProto.ToURLRequest.newBuilder().also { it.setRemote(sampleStruct()) },
                    RemoteProto.ToURLResponse.newBuilder().also { it.setUrl("a") },
                    RemoteProto.GetParametersRequest.newBuilder().also { it.setRemote(sampleStruct()) },
                    RemoteProto.GetParametersResponse.newBuilder().also { it.setParameters(sampleStruct()) },
                    RemoteProto.ValidateRemoteRequest.newBuilder().also { it.setRemote(sampleStruct()) },
                    RemoteProto.ValidateRemoteResponse.newBuilder(),
                    RemoteProto.ValidateParametersRequest.newBuilder().also { it.setParameters(sampleStruct()) },
                    RemoteProto.ValidateParametersResponse.newBuilder(),
                    RemoteProto.Tag.newBuilder().also { it.setKey("a") },
                    RemoteProto.Commit.newBuilder().also { it.setId("a") },
                    RemoteProto.GetCommitRequest.newBuilder().also { it.setCommitId("a") },
                    RemoteProto.GetCommitResponse.newBuilder().also { it.setCommitNull(true) },
                    RemoteProto.ListCommitRequest.newBuilder(),
                    RemoteProto.ListCommitResponse.newBuilder(),
                    RemoteProto.RemoteType.newBuilder().also { it.setType("a") },
                    RemoteProto.ExtendedURL.newBuilder().also { it.setUrl("a") },
                    RemoteProto.RemoteProperties.newBuilder().also { it.setValues(sampleStruct()) },
                    RemoteProto.ParameterProperties.newBuilder().also { it.setValues(sampleStruct()) },
                )
            for (b in builders) {
                io.kotlintest.shouldThrow<IllegalArgumentException> {
                    b.mergeFrom(foreign)
                }
            }
        }

        "every Builder.isInitialized returns true on a fresh builder (covers the override on each)" {
            // The generated isInitialized() override on every Builder simply returns true
            // for proto3 messages without required fields. Touching it on every builder
            // ensures the override is loaded and reported as covered.
            RemoteProto.GetTypeRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.GetTypeResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.FromURLRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.FromURLResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.ToURLRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.ToURLResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.GetParametersRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.GetParametersResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.ValidateRemoteRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.ValidateRemoteResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.ValidateParametersRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.ValidateParametersResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.Tag.newBuilder().isInitialized shouldBe true
            RemoteProto.Commit.newBuilder().isInitialized shouldBe true
            RemoteProto.GetCommitRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.GetCommitResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.ListCommitRequest.newBuilder().isInitialized shouldBe true
            RemoteProto.ListCommitResponse.newBuilder().isInitialized shouldBe true
            RemoteProto.RemoteType.newBuilder().isInitialized shouldBe true
            RemoteProto.ExtendedURL.newBuilder().isInitialized shouldBe true
            RemoteProto.RemoteProperties.newBuilder().isInitialized shouldBe true
            RemoteProto.ParameterProperties.newBuilder().isInitialized shouldBe true
        }

        "every parser surfaces InvalidProtocolBufferException on truncated input" {
            // Feed each message's parser a truncated wire payload — a varint tag that claims
            // a length-delimited submessage of N bytes followed by fewer than N bytes. This
            // hits the InvalidProtocolBufferException catch branch in the generated
            // parsePartialFrom anonymous-class Parser.
            val truncated =
                byteArrayOf(
                    // Tag for field 1, wire type 2 (length-delimited) = 0x0A.
                    0x0A.toByte(),
                    // Claimed length: 50 bytes
                    50,
                    // Only 1 byte of payload — definitely shorter than 50.
                    'x'.code.toByte(),
                )
            val parsers =
                listOf(
                    RemoteProto.GetTypeResponse.parser(),
                    RemoteProto.FromURLRequest.parser(),
                    RemoteProto.FromURLResponse.parser(),
                    RemoteProto.ToURLRequest.parser(),
                    RemoteProto.ToURLResponse.parser(),
                    RemoteProto.GetParametersRequest.parser(),
                    RemoteProto.GetParametersResponse.parser(),
                    RemoteProto.ValidateRemoteRequest.parser(),
                    RemoteProto.ValidateParametersRequest.parser(),
                    RemoteProto.Tag.parser(),
                    RemoteProto.Commit.parser(),
                    RemoteProto.GetCommitRequest.parser(),
                    RemoteProto.GetCommitResponse.parser(),
                    RemoteProto.ListCommitRequest.parser(),
                    RemoteProto.ListCommitResponse.parser(),
                    RemoteProto.RemoteType.parser(),
                    RemoteProto.ExtendedURL.parser(),
                    RemoteProto.RemoteProperties.parser(),
                    RemoteProto.ParameterProperties.parser(),
                )
            for (parser in parsers) {
                io.kotlintest.shouldThrow<com.google.protobuf.InvalidProtocolBufferException> {
                    parser.parseFrom(truncated)
                }
            }
        }

        "setXxx(null) throws NullPointerException for nested-message fields" {
            // The generated setXxx(value) checks `if (value == null) throw new NPE()`. This
            // covers that explicit null-guard on every nested-Struct field.
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.FromURLResponse.newBuilder().setRemote(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ToURLRequest.newBuilder().setRemote(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.GetParametersRequest.newBuilder().setRemote(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.GetParametersResponse.newBuilder().setParameters(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ValidateRemoteRequest.newBuilder().setRemote(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ValidateParametersRequest.newBuilder().setParameters(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.Commit.newBuilder().setProperties(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.GetCommitRequest.newBuilder().setRemote(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ListCommitRequest.newBuilder().setRemote(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.RemoteProperties.newBuilder().setValues(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ParameterProperties.newBuilder().setValues(null as com.google.protobuf.Struct?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.GetCommitResponse.newBuilder().setCommitValue(null as RemoteProto.Commit?)
            }
        }

        "field-level reflection via getField walks internalGetFieldAccessorTable on every message" {
            // Calling msg.getField(descriptor.findFieldByNumber(...)) routes through the
            // generated internalGetFieldAccessorTable() to look up the FieldAccessor. That
            // method body is otherwise unreachable from public APIs.
            val pairs =
                listOf<Pair<com.google.protobuf.Message, Int>>(
                    RemoteProto.GetTypeResponse
                        .newBuilder()
                        .setType("x")
                        .build() to 1,
                    RemoteProto.FromURLRequest
                        .newBuilder()
                        .setUrl("x")
                        .build() to 1,
                    RemoteProto.FromURLResponse
                        .newBuilder()
                        .setRemote(sampleStruct())
                        .build() to 1,
                    RemoteProto.ToURLRequest
                        .newBuilder()
                        .setRemote(sampleStruct())
                        .build() to 1,
                    RemoteProto.ToURLResponse
                        .newBuilder()
                        .setUrl("x")
                        .build() to 1,
                    RemoteProto.GetParametersRequest
                        .newBuilder()
                        .setRemote(sampleStruct())
                        .build() to 1,
                    RemoteProto.GetParametersResponse
                        .newBuilder()
                        .setParameters(sampleStruct())
                        .build() to 1,
                    RemoteProto.ValidateRemoteRequest
                        .newBuilder()
                        .setRemote(sampleStruct())
                        .build() to 1,
                    RemoteProto.ValidateParametersRequest
                        .newBuilder()
                        .setParameters(sampleStruct())
                        .build() to 1,
                    RemoteProto.Tag
                        .newBuilder()
                        .setKey("k")
                        .build() to 1,
                    RemoteProto.Commit
                        .newBuilder()
                        .setId("c")
                        .build() to 1,
                    RemoteProto.GetCommitRequest
                        .newBuilder()
                        .setCommitId("c")
                        .build() to 3,
                    RemoteProto.GetCommitResponse
                        .newBuilder()
                        .setCommitNull(true)
                        .build() to 1,
                    RemoteProto.ListCommitRequest
                        .newBuilder()
                        .setRemote(sampleStruct())
                        .build() to 1,
                    RemoteProto.ListCommitResponse
                        .newBuilder()
                        .addCommits(
                            RemoteProto.Commit
                                .newBuilder()
                                .setId("c")
                                .build(),
                        ).build() to 1,
                    RemoteProto.RemoteType
                        .newBuilder()
                        .setType("x")
                        .build() to 1,
                    RemoteProto.ExtendedURL
                        .newBuilder()
                        .setUrl("x")
                        .build() to 1,
                    RemoteProto.RemoteProperties
                        .newBuilder()
                        .setValues(sampleStruct())
                        .build() to 1,
                    RemoteProto.ParameterProperties
                        .newBuilder()
                        .setValues(sampleStruct())
                        .build() to 1,
                )
            for ((msg, fieldNum) in pairs) {
                val descriptor = msg.descriptorForType
                val fd = descriptor.findFieldByNumber(fieldNum)
                msg.getField(fd) shouldNotBe null
                // toBuilder().getField also walks the Builder-side accessor table.
                msg.toBuilder().getField(fd) shouldNotBe null
                // allFields exposes every set field via the accessor table at once.
                msg.allFields shouldNotBe null
            }
        }

        "newBuilderForType(BuilderParent) covers the parent-receiving constructor on every message" {
            // The protected newBuilderForType(BuilderParent parent) factory is invoked
            // internally when a SingleFieldBuilder needs a sub-builder. We can also call it
            // directly via reflection to ensure the `Builder(parent)` constructor and the
            // factory line both count as covered for every top-level message.
            val outer = RemoteProto::class.java
            val nestedNames =
                listOf(
                    "GetTypeRequest",
                    "GetTypeResponse",
                    "FromURLRequest",
                    "FromURLResponse",
                    "ToURLRequest",
                    "ToURLResponse",
                    "GetParametersRequest",
                    "GetParametersResponse",
                    "ValidateRemoteRequest",
                    "ValidateRemoteResponse",
                    "ValidateParametersRequest",
                    "ValidateParametersResponse",
                    "Tag",
                    "Commit",
                    "GetCommitRequest",
                    "GetCommitResponse",
                    "ListCommitRequest",
                    "ListCommitResponse",
                    "RemoteType",
                    "ExtendedURL",
                    "RemoteProperties",
                    "ParameterProperties",
                )
            val builderParentCls = Class.forName("com.google.protobuf.AbstractMessage\$BuilderParent")
            // No-op BuilderParent: markDirty() is the single interface method and we don't care
            // about the callback for a coverage-only test.
            val noopParent =
                java.lang.reflect.Proxy.newProxyInstance(
                    builderParentCls.classLoader,
                    arrayOf(builderParentCls),
                ) { _, _, _ -> null }
            for (name in nestedNames) {
                val msgCls = Class.forName("${outer.name}\$$name")
                val defaultInstance = msgCls.getMethod("getDefaultInstance").invoke(null)
                val method =
                    msgCls.getDeclaredMethod("newBuilderForType", builderParentCls)
                method.isAccessible = true
                val builder = method.invoke(defaultInstance, noopParent)
                builder shouldNotBe null
            }
        }

        "Builder-side string accessors: clearXxx + setXxxBytes(null) covers Builder NPE guards" {
            // The clearXxx/setXxxBytes Builder methods carry distinct lines from the
            // Message-side getters. Cover them on every string-bearing message.
            val s = "v"
            RemoteProto.GetTypeResponse
                .newBuilder()
                .setType(s)
                .clearType()
                .build()
                .type shouldBe ""
            RemoteProto.RemoteType
                .newBuilder()
                .setType(s)
                .clearType()
                .build()
                .type shouldBe ""
            RemoteProto.FromURLRequest
                .newBuilder()
                .setUrl(s)
                .clearUrl()
                .build()
                .url shouldBe ""
            RemoteProto.ToURLResponse
                .newBuilder()
                .setUrl(s)
                .clearUrl()
                .build()
                .url shouldBe ""
            RemoteProto.ExtendedURL
                .newBuilder()
                .setUrl(s)
                .clearUrl()
                .build()
                .url shouldBe ""
            RemoteProto.Commit
                .newBuilder()
                .setId(s)
                .clearId()
                .build()
                .id shouldBe ""
            RemoteProto.GetCommitRequest
                .newBuilder()
                .setCommitId(s)
                .clearCommitId()
                .build()
                .commitId shouldBe ""
            RemoteProto.Tag
                .newBuilder()
                .setKey(s)
                .clearKey()
                .build()
                .key shouldBe ""

            // setXxxBytes(null) hits the explicit NPE guard on every Builder.
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.GetTypeResponse.newBuilder().setTypeBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.RemoteType.newBuilder().setTypeBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.FromURLRequest.newBuilder().setUrlBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ToURLResponse.newBuilder().setUrlBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ExtendedURL.newBuilder().setUrlBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.Commit.newBuilder().setIdBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.GetCommitRequest.newBuilder().setCommitIdBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.Tag.newBuilder().setKeyBytes(null)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueStringBytes(null)
            }
        }

        "Builder-side map accessors expose the same read surface as the built Message" {
            // For every message with a `map<string,string>` field, the Builder's read-side
            // accessors (getXxxCount, containsXxx, getXxxMap, getXxxOrDefault, getXxxOrThrow,
            // clearXxx) carry their own line counts in jacoco. Exercise them all.
            val builder1 =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u")
                    .putProperties("a", "1")
                    .putProperties("b", "2")
            builder1.propertiesCount shouldBe 2
            builder1.containsProperties("a") shouldBe true
            builder1.containsProperties("missing") shouldBe false
            builder1.propertiesMap.size shouldBe 2
            builder1.getPropertiesOrDefault("a", "fallback") shouldBe "1"
            builder1.getPropertiesOrDefault("missing", "fallback") shouldBe "fallback"
            builder1.getPropertiesOrThrow("a") shouldBe "1"
            io.kotlintest.shouldThrow<IllegalArgumentException> {
                builder1.getPropertiesOrThrow("missing")
            }
            builder1.putAllProperties(mapOf("c" to "3", "d" to "4"))
            builder1.removeProperties("a")
            builder1.propertiesCount shouldBe 3
            builder1.clearProperties()
            builder1.propertiesCount shouldBe 0

            val builder2 =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("u")
                    .putProperties("a", "1")
            builder2.propertiesCount shouldBe 1
            builder2.containsProperties("a") shouldBe true
            builder2.propertiesMap["a"] shouldBe "1"
            builder2.getPropertiesOrDefault("missing", "fb") shouldBe "fb"
            builder2.getPropertiesOrThrow("a") shouldBe "1"
            builder2.removeProperties("a")
            builder2.clearProperties()

            val builder3 =
                RemoteProto.ExtendedURL
                    .newBuilder()
                    .setUrl("u")
                    .putValues("a", "1")
                    .putAllValues(mapOf("b" to "2"))
            builder3.valuesCount shouldBe 2
            builder3.containsValues("a") shouldBe true
            builder3.valuesMap["a"] shouldBe "1"
            builder3.getValuesOrDefault("missing", "fb") shouldBe "fb"
            builder3.getValuesOrThrow("a") shouldBe "1"
            io.kotlintest.shouldThrow<IllegalArgumentException> { builder3.getValuesOrThrow("missing") }
            builder3.removeValues("a")
            builder3.clearValues()
            builder3.valuesCount shouldBe 0
        }

        "repeated-field operations with RepeatedFieldBuilder allocated cover the else-branches" {
            // When commitsBuilder_ / tagsBuilder_ is allocated (via getCommitsBuilder etc.),
            // every set/add/remove/get on the repeated field goes through the else-branch in
            // the generated Builder. Cover both branches by first allocating the field
            // builder, then walking the full accessor surface.

            // --- ListCommitResponse.commits ---
            val lcr = RemoteProto.ListCommitResponse.newBuilder()
            lcr.addCommits(
                RemoteProto.Commit
                    .newBuilder()
                    .setId("a")
                    .build(),
            )
            lcr.commitsBuilderList shouldNotBe null
            lcr.getCommitsBuilder(0).id shouldBe "a"
            lcr.commitsCount shouldBe 1
            lcr.getCommits(0).id shouldBe "a"
            lcr.commitsList.size shouldBe 1
            lcr.setCommits(
                0,
                RemoteProto.Commit
                    .newBuilder()
                    .setId("aprime")
                    .build(),
            )
            lcr.getCommits(0).id shouldBe "aprime"
            lcr.setCommits(0, RemoteProto.Commit.newBuilder().setId("aprime2"))
            lcr.getCommits(0).id shouldBe "aprime2"
            lcr.addCommits(
                RemoteProto.Commit
                    .newBuilder()
                    .setId("b")
                    .build(),
            )
            lcr.addCommits(
                0,
                RemoteProto.Commit
                    .newBuilder()
                    .setId("zero")
                    .build(),
            )
            lcr.addCommits(1, RemoteProto.Commit.newBuilder().setId("between"))
            lcr.addCommits(RemoteProto.Commit.newBuilder().setId("end"))
            lcr.addAllCommits(
                listOf(
                    RemoteProto.Commit
                        .newBuilder()
                        .setId("more")
                        .build(),
                ),
            )
            lcr.addCommitsBuilder().setId("freshly-appended")
            lcr.addCommitsBuilder(0).setId("freshly-prepended")
            lcr.removeCommits(0)
            lcr.getCommitsOrBuilder(0) shouldNotBe null
            lcr.commitsOrBuilderList.size shouldBe lcr.commitsCount
            lcr.clearCommits()
            lcr.commitsCount shouldBe 0

            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("c")
                            .build(),
                    ).setCommits(0, null as RemoteProto.Commit?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ListCommitResponse.newBuilder().addCommits(null as RemoteProto.Commit?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ListCommitResponse.newBuilder().addCommits(0, null as RemoteProto.Commit?)
            }

            // --- ListCommitRequest.tags ---
            val lcrq = RemoteProto.ListCommitRequest.newBuilder()
            lcrq.addTags(
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("a")
                    .setValueString("v")
                    .build(),
            )
            lcrq.tagsBuilderList shouldNotBe null
            lcrq.getTagsBuilder(0).key shouldBe "a"
            lcrq.tagsCount shouldBe 1
            lcrq.getTags(0).key shouldBe "a"
            lcrq.tagsList.size shouldBe 1
            lcrq.setTags(
                0,
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("aprime")
                    .setValueString("v")
                    .build(),
            )
            lcrq.setTags(
                0,
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("aprime2")
                    .setValueString("v"),
            )
            lcrq.addTags(
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("b")
                    .setValueString("v")
                    .build(),
            )
            lcrq.addTags(
                0,
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("zero")
                    .setValueString("v")
                    .build(),
            )
            lcrq.addTags(
                1,
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("between")
                    .setValueString("v"),
            )
            lcrq.addTags(
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("end")
                    .setValueString("v"),
            )
            lcrq.addAllTags(
                listOf(
                    RemoteProto.Tag
                        .newBuilder()
                        .setKey("more")
                        .setValueString("v")
                        .build(),
                ),
            )
            lcrq.addTagsBuilder().setKey("fresh-appended").setValueString("v")
            lcrq.addTagsBuilder(0).setKey("fresh-prepended").setValueString("v")
            lcrq.removeTags(0)
            lcrq.getTagsOrBuilder(0) shouldNotBe null
            lcrq.tagsOrBuilderList.size shouldBe lcrq.tagsCount
            lcrq.clearTags()
            lcrq.tagsCount shouldBe 0

            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("a")
                            .build(),
                    ).setTags(0, null as RemoteProto.Tag?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ListCommitRequest.newBuilder().addTags(null as RemoteProto.Tag?)
            }
            io.kotlintest.shouldThrow<NullPointerException> {
                RemoteProto.ListCommitRequest.newBuilder().addTags(0, null as RemoteProto.Tag?)
            }
        }

        "Message-side map accessors: getXxxOrThrow + getXxxOrDefault on missing keys" {
            // For every map-bearing message, the Message-side carries its own
            // getXxxOrThrow / getXxxOrDefault that JaCoCo counts independently from the
            // Builder-side accessors. Walk both with both present and missing keys.
            val from =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u")
                    .putProperties("a", "1")
                    .build()
            from.getPropertiesOrDefault("a", "x") shouldBe "1"
            from.getPropertiesOrDefault("missing", "fb") shouldBe "fb"
            from.getPropertiesOrThrow("a") shouldBe "1"
            io.kotlintest.shouldThrow<IllegalArgumentException> { from.getPropertiesOrThrow("missing") }

            val to =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("u")
                    .putProperties("a", "1")
                    .build()
            to.getPropertiesOrDefault("a", "x") shouldBe "1"
            to.getPropertiesOrDefault("missing", "fb") shouldBe "fb"
            to.getPropertiesOrThrow("a") shouldBe "1"
            io.kotlintest.shouldThrow<IllegalArgumentException> { to.getPropertiesOrThrow("missing") }

            val ext =
                RemoteProto.ExtendedURL
                    .newBuilder()
                    .setUrl("u")
                    .putValues("a", "1")
                    .build()
            ext.getValuesOrDefault("a", "x") shouldBe "1"
            ext.getValuesOrDefault("missing", "fb") shouldBe "fb"
            ext.getValuesOrThrow("a") shouldBe "1"
            io.kotlintest.shouldThrow<IllegalArgumentException> { ext.getValuesOrThrow("missing") }
        }

        "Tag.Builder oneof accessors cover both set + unset states of every variant" {
            // The Builder exposes hasValueNull / getValueNull / clearValueNull (and the same
            // for value_string) — each Builder-side accessor is a distinct line. Walk both
            // states (set and unset) on both variants so the JaCoCo branches fire.
            val emptyBuilder = RemoteProto.Tag.newBuilder()
            // unset state: hasXxx false, getXxx returns the default
            emptyBuilder.hasValueNull() shouldBe false
            emptyBuilder.valueNull shouldBe false
            emptyBuilder.hasValueString() shouldBe false
            emptyBuilder.valueString shouldBe ""
            emptyBuilder.valueStringBytes.toStringUtf8() shouldBe ""

            // value_null set
            val nullBuilder =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueNull(true)
            nullBuilder.hasValueNull() shouldBe true
            nullBuilder.valueNull shouldBe true
            nullBuilder.hasValueString() shouldBe false
            // Reading value_string while the oneof is set to value_null returns "" / empty.
            nullBuilder.valueString shouldBe ""
            nullBuilder.valueStringBytes.toStringUtf8() shouldBe ""
            // clearValueNull when valueCase is VALUE_NULL drops the oneof back to NOT_SET.
            val cleared1 = nullBuilder.clone().clearValueNull().build()
            cleared1.valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_NOT_SET
            // clearValueNull when valueCase is *not* VALUE_NULL is a no-op (covers the if-false branch).
            val cleared2 =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueString("v")
                    .clearValueNull()
                    .build()
            cleared2.valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_STRING

            // value_string set
            val strBuilder =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueString("v")
            strBuilder.hasValueString() shouldBe true
            strBuilder.valueString shouldBe "v"
            strBuilder.valueStringBytes.toStringUtf8() shouldBe "v"
            strBuilder.hasValueNull() shouldBe false
            strBuilder.valueNull shouldBe false
            // clearValueString when valueCase is VALUE_STRING drops the oneof.
            val cleared3 = strBuilder.clone().clearValueString().build()
            cleared3.valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_NOT_SET
            // clearValueString when valueCase is not VALUE_STRING is a no-op.
            val cleared4 =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueNull(true)
                    .clearValueString()
                    .build()
            cleared4.valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_NULL

            // value_string set via Bytes — get reads the bytes branch.
            val bytesBuilder =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("k")
                    .setValueStringBytes(
                        com.google.protobuf.ByteString
                            .copyFromUtf8("vb"),
                    )
            bytesBuilder.valueString shouldBe "vb"
            bytesBuilder.valueStringBytes.toStringUtf8() shouldBe "vb"
        }

        "GetCommitResponse.Builder oneof accessors cover both set + unset states of every variant" {
            // Mirror of the Tag test but for the GetCommitResponse oneof.
            val emptyBuilder = RemoteProto.GetCommitResponse.newBuilder()
            emptyBuilder.hasCommitNull() shouldBe false
            emptyBuilder.commitNull shouldBe false
            emptyBuilder.hasCommitValue() shouldBe false
            emptyBuilder.commitValue shouldBe RemoteProto.Commit.getDefaultInstance()

            val nullBuilder = RemoteProto.GetCommitResponse.newBuilder().setCommitNull(true)
            nullBuilder.hasCommitNull() shouldBe true
            nullBuilder.commitNull shouldBe true
            nullBuilder.hasCommitValue() shouldBe false
            nullBuilder.commitValue shouldBe RemoteProto.Commit.getDefaultInstance()
            // clearCommitNull when valueCase is COMMIT_NULL drops the oneof.
            val cleared1 = nullBuilder.clone().clearCommitNull().build()
            cleared1.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_NOT_SET
            // clearCommitNull when valueCase is *not* COMMIT_NULL is a no-op.
            val cleared2 =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitValue(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("x")
                            .build(),
                    ).clearCommitNull()
                    .build()
            cleared2.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_VALUE

            val valueBuilder =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitValue(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("y")
                            .build(),
                    )
            valueBuilder.hasCommitValue() shouldBe true
            valueBuilder.commitValue.id shouldBe "y"
            valueBuilder.hasCommitNull() shouldBe false
            // clearCommitValue when set drops the oneof.
            val cleared3 = valueBuilder.clone().clearCommitValue().build()
            cleared3.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_NOT_SET
            // clearCommitValue when not set is a no-op.
            val cleared4 =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitNull(true)
                    .clearCommitValue()
                    .build()
            cleared4.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_NULL
        }

        "clear() after allocating nested-message field builders walks the dispose branches" {
            // The generated clear() has `if (xxxBuilder_ != null) { dispose; xxxBuilder_ = null }`
            // for every nested-message field. Cover that branch by allocating the field
            // builder first, then calling clear.
            fun verifyClear(
                builder: com.google.protobuf.Message.Builder,
                allocators: List<(com.google.protobuf.Message.Builder) -> Unit>,
            ) {
                allocators.forEach { it(builder) }
                // clear() must not throw and must reset to default.
                builder.clear()
                builder.build() shouldBe builder.defaultInstanceForType
            }

            verifyClear(
                RemoteProto.FromURLResponse.newBuilder(),
                listOf { (it as RemoteProto.FromURLResponse.Builder).remoteBuilder },
            )
            verifyClear(
                RemoteProto.ToURLRequest.newBuilder(),
                listOf { (it as RemoteProto.ToURLRequest.Builder).remoteBuilder },
            )
            verifyClear(
                RemoteProto.GetParametersRequest.newBuilder(),
                listOf { (it as RemoteProto.GetParametersRequest.Builder).remoteBuilder },
            )
            verifyClear(
                RemoteProto.GetParametersResponse.newBuilder(),
                listOf { (it as RemoteProto.GetParametersResponse.Builder).parametersBuilder },
            )
            verifyClear(
                RemoteProto.ValidateRemoteRequest.newBuilder(),
                listOf { (it as RemoteProto.ValidateRemoteRequest.Builder).remoteBuilder },
            )
            verifyClear(
                RemoteProto.ValidateParametersRequest.newBuilder(),
                listOf { (it as RemoteProto.ValidateParametersRequest.Builder).parametersBuilder },
            )
            verifyClear(
                RemoteProto.Commit.newBuilder(),
                listOf { (it as RemoteProto.Commit.Builder).propertiesBuilder },
            )
            verifyClear(
                RemoteProto.RemoteProperties.newBuilder(),
                listOf { (it as RemoteProto.RemoteProperties.Builder).valuesBuilder },
            )
            verifyClear(
                RemoteProto.ParameterProperties.newBuilder(),
                listOf { (it as RemoteProto.ParameterProperties.Builder).valuesBuilder },
            )
            // Both-field messages: clear with both field builders allocated.
            verifyClear(
                RemoteProto.GetCommitRequest.newBuilder(),
                listOf(
                    { (it as RemoteProto.GetCommitRequest.Builder).remoteBuilder },
                    { (it as RemoteProto.GetCommitRequest.Builder).parametersBuilder },
                ),
            )
            verifyClear(
                RemoteProto.ListCommitRequest.newBuilder(),
                listOf(
                    { (it as RemoteProto.ListCommitRequest.Builder).remoteBuilder },
                    { (it as RemoteProto.ListCommitRequest.Builder).parametersBuilder },
                    { (it as RemoteProto.ListCommitRequest.Builder).tagsBuilderList },
                ),
            )
            verifyClear(
                RemoteProto.ListCommitResponse.newBuilder(),
                listOf { (it as RemoteProto.ListCommitResponse.Builder).commitsBuilderList },
            )
            verifyClear(
                RemoteProto.GetCommitResponse.newBuilder(),
                listOf {
                    (it as RemoteProto.GetCommitResponse.Builder).commitValueBuilder
                },
            )
        }

        "setXxx(Builder) after allocating the field builder walks the builder-allocated overload" {
            // Cover setRemote(Builder) on every Struct-bearing message after allocating the
            // field builder, so the `xxxBuilder_ != null` branch in setXxx(Builder) fires.
            val sb = Struct.newBuilder().putFields("k", Value.newBuilder().setStringValue("v").build())

            val b1 = RemoteProto.FromURLResponse.newBuilder()
            b1.remoteBuilder
            b1.setRemote(sb)
            b1.hasRemote() shouldBe true

            val b2 = RemoteProto.ToURLRequest.newBuilder()
            b2.remoteBuilder
            b2.setRemote(sb)
            b2.hasRemote() shouldBe true

            val b3 = RemoteProto.GetParametersRequest.newBuilder()
            b3.remoteBuilder
            b3.setRemote(sb)
            b3.hasRemote() shouldBe true

            val b4 = RemoteProto.GetParametersResponse.newBuilder()
            b4.parametersBuilder
            b4.setParameters(sb)
            b4.hasParameters() shouldBe true

            val b5 = RemoteProto.ValidateRemoteRequest.newBuilder()
            b5.remoteBuilder
            b5.setRemote(sb)
            b5.hasRemote() shouldBe true

            val b6 = RemoteProto.ValidateParametersRequest.newBuilder()
            b6.parametersBuilder
            b6.setParameters(sb)
            b6.hasParameters() shouldBe true

            val b7 = RemoteProto.Commit.newBuilder()
            b7.propertiesBuilder
            b7.setProperties(sb)
            b7.hasProperties() shouldBe true

            val b8 = RemoteProto.GetCommitRequest.newBuilder()
            b8.remoteBuilder
            b8.setRemote(sb)
            b8.parametersBuilder
            b8.setParameters(sb)
            b8.hasRemote() shouldBe true
            b8.hasParameters() shouldBe true

            val b9 = RemoteProto.ListCommitRequest.newBuilder()
            b9.remoteBuilder
            b9.setRemote(sb)
            b9.parametersBuilder
            b9.setParameters(sb)
            b9.hasRemote() shouldBe true
            b9.hasParameters() shouldBe true

            val b10 = RemoteProto.RemoteProperties.newBuilder()
            b10.valuesBuilder
            b10.setValues(sb)
            b10.hasValues() shouldBe true

            val b11 = RemoteProto.ParameterProperties.newBuilder()
            b11.valuesBuilder
            b11.setValues(sb)
            b11.hasValues() shouldBe true

            // GetCommitResponse.commit_value (Commit-typed)
            val b12 = RemoteProto.GetCommitResponse.newBuilder()
            b12.commitValueBuilder
            b12.setCommitValue(RemoteProto.Commit.newBuilder().setId("x"))
            b12.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_VALUE
        }

        "mergeFrom into a Builder with the RepeatedFieldBuilder allocated covers the merge-into-builder branches" {
            // After allocating the repeated-field builder (via getCommitsBuilder etc.), a
            // subsequent mergeFrom walks the `commitsBuilder_ != null` branch in mergeFrom.
            // Within that branch there are two sub-branches: builder empty vs. populated.

            // (1) commitsBuilder_ allocated, commitsBuilder_ empty, source populated.
            val a = RemoteProto.ListCommitResponse.newBuilder()
            a.commitsBuilderList // allocate the RepeatedFieldBuilder while empty
            a.mergeFrom(
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("from")
                            .build(),
                    ).build(),
            )
            a.commitsCount shouldBe 1
            a.build().commitsCount shouldBe 1

            // (2) commitsBuilder_ allocated, commitsBuilder_ populated, source populated.
            val b =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("existing")
                            .build(),
                    )
            b.commitsBuilderList // allocate after populating
            b.mergeFrom(
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("merged")
                            .build(),
                    ).build(),
            )
            b.commitsCount shouldBe 2
            b.build().commitsCount shouldBe 2

            // Same for ListCommitRequest.tags.
            val c = RemoteProto.ListCommitRequest.newBuilder()
            c.tagsBuilderList
            c.mergeFrom(
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k")
                            .setValueString("v")
                            .build(),
                    ).build(),
            )
            c.tagsCount shouldBe 1

            val d =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k0")
                            .setValueString("v")
                            .build(),
                    )
            d.tagsBuilderList
            d.mergeFrom(
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k1")
                            .setValueString("v")
                            .build(),
                    ).build(),
            )
            d.tagsCount shouldBe 2
        }

        "mergeFrom into an empty Builder where the source's repeated field is also empty is a no-op" {
            // Covers the early-return branches in mergeFrom for repeated fields where neither
            // side has any entries.
            val a =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .mergeFrom(RemoteProto.ListCommitResponse.getDefaultInstance())
                    .build()
            a.commitsCount shouldBe 0

            val b =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .mergeFrom(RemoteProto.ListCommitRequest.getDefaultInstance())
                    .build()
            b.tagsCount shouldBe 0
        }

        "every Builder.mergeFrom(CodedInputStream, null) throws NPE" {
            // Walks the explicit `if (extensionRegistry == null) throw NPE` guard in every
            // generated Builder.mergeFrom(CodedInputStream, ExtensionRegistryLite) method.
            val builders =
                listOf<com.google.protobuf.Message.Builder>(
                    RemoteProto.GetTypeRequest.newBuilder(),
                    RemoteProto.GetTypeResponse.newBuilder(),
                    RemoteProto.FromURLRequest.newBuilder(),
                    RemoteProto.FromURLResponse.newBuilder(),
                    RemoteProto.ToURLRequest.newBuilder(),
                    RemoteProto.ToURLResponse.newBuilder(),
                    RemoteProto.GetParametersRequest.newBuilder(),
                    RemoteProto.GetParametersResponse.newBuilder(),
                    RemoteProto.ValidateRemoteRequest.newBuilder(),
                    RemoteProto.ValidateRemoteResponse.newBuilder(),
                    RemoteProto.ValidateParametersRequest.newBuilder(),
                    RemoteProto.ValidateParametersResponse.newBuilder(),
                    RemoteProto.Tag.newBuilder(),
                    RemoteProto.Commit.newBuilder(),
                    RemoteProto.GetCommitRequest.newBuilder(),
                    RemoteProto.GetCommitResponse.newBuilder(),
                    RemoteProto.ListCommitRequest.newBuilder(),
                    RemoteProto.ListCommitResponse.newBuilder(),
                    RemoteProto.RemoteType.newBuilder(),
                    RemoteProto.ExtendedURL.newBuilder(),
                    RemoteProto.RemoteProperties.newBuilder(),
                    RemoteProto.ParameterProperties.newBuilder(),
                )
            for (b in builders) {
                io.kotlintest.shouldThrow<NullPointerException> {
                    b.mergeFrom(
                        com.google.protobuf.CodedInputStream
                            .newInstance(ByteArray(0)),
                        null as com.google.protobuf.ExtensionRegistryLite?,
                    )
                }
            }
        }

        "map-typed mergeFrom merges entries from source into target" {
            val source =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u-src")
                    .putProperties("a", "1")
                    .putProperties("b", "2")
                    .build()
            val intoPopulated =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u-dst")
                    .putProperties("a", "old")
                    .putProperties("c", "3")
                    .mergeFrom(source)
                    .build()
            intoPopulated.url shouldBe "u-src"
            intoPopulated.propertiesMap["a"] shouldBe "1"
            intoPopulated.propertiesMap["b"] shouldBe "2"
            intoPopulated.propertiesMap["c"] shouldBe "3"
        }
    }

    /**
     * Exercises the message-builder-typed setter overload (setXxx(Xxx.Builder)) on a
     * representative message. Kotlin generics make this awkward to pass via a lambda
     * above, so we keep this as a separate seam.
     */
    private fun setStructViaBuilderTypedOverload(b: com.google.protobuf.Message.Builder) {
        val structBuilder = Struct.newBuilder().putFields("via", Value.newBuilder().setStringValue("b").build())
        when (b) {
            is RemoteProto.FromURLResponse.Builder -> b.setRemote(structBuilder)
            is RemoteProto.ToURLRequest.Builder -> b.setRemote(structBuilder)
            is RemoteProto.GetParametersRequest.Builder -> b.setRemote(structBuilder)
            is RemoteProto.GetParametersResponse.Builder -> b.setParameters(structBuilder)
            is RemoteProto.ValidateRemoteRequest.Builder -> b.setRemote(structBuilder)
            is RemoteProto.ValidateParametersRequest.Builder -> b.setParameters(structBuilder)
            is RemoteProto.Commit.Builder -> b.setProperties(structBuilder)
            is RemoteProto.ListCommitRequest.Builder -> b.setRemote(structBuilder)
            is RemoteProto.GetCommitRequest.Builder -> b.setRemote(structBuilder)
            is RemoteProto.RemoteProperties.Builder -> b.setValues(structBuilder)
            is RemoteProto.ParameterProperties.Builder -> b.setValues(structBuilder)
            else -> Unit
        }
    }

    // No-op helper so the test name above stays meaningful; building a no-op Unit return
    // value lets the assertion be a single expression matching kotlintest's StringSpec style.
    private fun RemoteProto.ListCommitResponse.Builder.tagsViaCommits() {
        // Touching commitsOrBuilderList completes the read-side surface for the repeated
        // message field on ListCommitResponse.
        require(this.commitsOrBuilderList.size == this.commitsCount)
    }
}
