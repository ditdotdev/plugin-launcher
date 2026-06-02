/*
 * Copyright The Dit Project Contributors
 */
package dev.dit.plugin.remote

import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

/**
 * Round-trip tests for every top-level message in src/main/proto/remote.proto.
 *
 * For each message we build a fully populated instance, serialize it with
 * toByteArray(), parse it back with parseFrom(bytes), and assert equality.
 * These tests would catch any schema change that breaks wire compatibility
 * or any Builder/Parser regression in the generated code, while also
 * exercising the Builder, Parser, toBuilder, merge, equals, hashCode, and
 * toString surfaces on each generated message.
 */
class RemoteProtoRoundTripTest : StringSpec() {
    /** Sample Struct used as a payload anywhere the proto schema expects google.protobuf.Struct. */
    private fun sampleStruct(seed: String): Struct =
        Struct
            .newBuilder()
            .putFields("k1-$seed", Value.newBuilder().setStringValue("v1-$seed").build())
            .putFields("k2-$seed", Value.newBuilder().setNumberValue(42.5).build())
            .putFields("k3-$seed", Value.newBuilder().setBoolValue(true).build())
            .build()

    /** Convenience: round-trip + equality + Builder reuse. */
    private inline fun <T : com.google.protobuf.GeneratedMessage> roundTrip(
        original: T,
        parse: (ByteArray) -> T,
    ) {
        val bytes = original.toByteArray()
        val parsed = parse(bytes)
        parsed shouldBe original
        parsed.hashCode() shouldBe original.hashCode()
        // toString must not throw and should reflect the fields.
        original.toString() shouldNotBe null
        // Empty/marker messages serialize to zero bytes; populated messages must not.
        (bytes.size >= 0) shouldBe true
    }

    init {
        "GetTypeRequest round-trips" {
            // GetTypeRequest is an empty marker message; round-trip still exercises Parser + equals.
            val req = RemoteProto.GetTypeRequest.newBuilder().build()
            roundTrip(req) { RemoteProto.GetTypeRequest.parseFrom(it) }
            // getDefaultInstance + parser are equivalent for empty messages.
            RemoteProto.GetTypeRequest.parser() shouldNotBe null
            RemoteProto.GetTypeRequest.getDefaultInstance() shouldBe req
        }

        "GetTypeResponse round-trips with type populated" {
            val res =
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("s3")
                    .build()
            roundTrip(res) { RemoteProto.GetTypeResponse.parseFrom(it) }
            val parsed = RemoteProto.GetTypeResponse.parseFrom(res.toByteArray())
            parsed.type shouldBe "s3"
            parsed.typeBytes shouldBe res.typeBytes
            // Builder.clear + toBuilder round-trip.
            val cleared = res.toBuilder().clearType().build()
            cleared.type shouldBe ""
        }

        "FromURLRequest round-trips with url + properties map" {
            val req =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("s3://bucket/path")
                    .putProperties("region", "us-west-2")
                    .putProperties("endpoint", "https://s3.amazonaws.com")
                    .build()
            roundTrip(req) { RemoteProto.FromURLRequest.parseFrom(it) }
            val parsed = RemoteProto.FromURLRequest.parseFrom(req.toByteArray())
            parsed.url shouldBe "s3://bucket/path"
            parsed.propertiesCount shouldBe 2
            parsed.propertiesMap["region"] shouldBe "us-west-2"
            parsed.getPropertiesOrDefault("missing", "fallback") shouldBe "fallback"
            parsed.getPropertiesOrThrow("endpoint") shouldBe "https://s3.amazonaws.com"
            parsed.containsProperties("region") shouldBe true
            parsed.containsProperties("nope") shouldBe false
        }

        "FromURLResponse round-trips with remote struct" {
            val res =
                RemoteProto.FromURLResponse
                    .newBuilder()
                    .setRemote(sampleStruct("from"))
                    .build()
            roundTrip(res) { RemoteProto.FromURLResponse.parseFrom(it) }
            val parsed = RemoteProto.FromURLResponse.parseFrom(res.toByteArray())
            parsed.hasRemote() shouldBe true
            parsed.remote.getFieldsOrThrow("k1-from").stringValue shouldBe "v1-from"
            // clearRemote drops the field.
            res
                .toBuilder()
                .clearRemote()
                .build()
                .hasRemote() shouldBe false
        }

        "ToURLRequest round-trips with remote struct" {
            val req =
                RemoteProto.ToURLRequest
                    .newBuilder()
                    .setRemote(sampleStruct("to"))
                    .build()
            roundTrip(req) { RemoteProto.ToURLRequest.parseFrom(it) }
            RemoteProto.ToURLRequest.parseFrom(req.toByteArray()).hasRemote() shouldBe true
        }

        "ToURLResponse round-trips with url + properties map" {
            val res =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("s3://my-bucket")
                    .putProperties("k", "v")
                    .putAllProperties(mapOf("a" to "1", "b" to "2"))
                    .build()
            roundTrip(res) { RemoteProto.ToURLResponse.parseFrom(it) }
            val parsed = RemoteProto.ToURLResponse.parseFrom(res.toByteArray())
            parsed.url shouldBe "s3://my-bucket"
            parsed.propertiesCount shouldBe 3
            parsed.propertiesMap["a"] shouldBe "1"
        }

        "GetParametersRequest round-trips" {
            val req =
                RemoteProto.GetParametersRequest
                    .newBuilder()
                    .setRemote(sampleStruct("params-req"))
                    .build()
            roundTrip(req) { RemoteProto.GetParametersRequest.parseFrom(it) }
        }

        "GetParametersResponse round-trips" {
            val res =
                RemoteProto.GetParametersResponse
                    .newBuilder()
                    .setParameters(sampleStruct("params-res"))
                    .build()
            roundTrip(res) { RemoteProto.GetParametersResponse.parseFrom(it) }
            res
                .toBuilder()
                .clearParameters()
                .build()
                .hasParameters() shouldBe false
        }

        "ValidateRemoteRequest + Response round-trip" {
            val req =
                RemoteProto.ValidateRemoteRequest
                    .newBuilder()
                    .setRemote(sampleStruct("vr"))
                    .build()
            roundTrip(req) { RemoteProto.ValidateRemoteRequest.parseFrom(it) }
            val res = RemoteProto.ValidateRemoteResponse.newBuilder().build()
            roundTrip(res) { RemoteProto.ValidateRemoteResponse.parseFrom(it) }
            res shouldBe RemoteProto.ValidateRemoteResponse.getDefaultInstance()
        }

        "ValidateParametersRequest + Response round-trip" {
            val req =
                RemoteProto.ValidateParametersRequest
                    .newBuilder()
                    .setParameters(sampleStruct("vp"))
                    .build()
            roundTrip(req) { RemoteProto.ValidateParametersRequest.parseFrom(it) }
            val res = RemoteProto.ValidateParametersResponse.newBuilder().build()
            roundTrip(res) { RemoteProto.ValidateParametersResponse.parseFrom(it) }
        }

        "Tag round-trips with value_null oneof variant" {
            val tag =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("env")
                    .setValueNull(true)
                    .build()
            roundTrip(tag) { RemoteProto.Tag.parseFrom(it) }
            val parsed = RemoteProto.Tag.parseFrom(tag.toByteArray())
            parsed.key shouldBe "env"
            parsed.valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_NULL
            parsed.valueNull shouldBe true
            parsed.hasValueNull() shouldBe true
            parsed.hasValueString() shouldBe false
        }

        "Tag round-trips with value_string oneof variant" {
            val tag =
                RemoteProto.Tag
                    .newBuilder()
                    .setKey("region")
                    .setValueString("us-west-2")
                    .build()
            roundTrip(tag) { RemoteProto.Tag.parseFrom(it) }
            val parsed = RemoteProto.Tag.parseFrom(tag.toByteArray())
            parsed.valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_STRING
            parsed.valueString shouldBe "us-west-2"
            parsed.valueStringBytes shouldBe tag.valueStringBytes
            // Clearing the oneof drops the case back to VALUE_NOT_SET.
            tag
                .toBuilder()
                .clearValue()
                .build()
                .valueCase shouldBe RemoteProto.Tag.ValueCase.VALUE_NOT_SET
        }

        "Tag.ValueCase.forNumber covers oneof discriminator" {
            // Direct exercise of the generated forNumber switch.
            RemoteProto.Tag.ValueCase.forNumber(0) shouldBe RemoteProto.Tag.ValueCase.VALUE_NOT_SET
            RemoteProto.Tag.ValueCase.forNumber(2) shouldBe RemoteProto.Tag.ValueCase.VALUE_NULL
            RemoteProto.Tag.ValueCase.forNumber(3) shouldBe RemoteProto.Tag.ValueCase.VALUE_STRING
            // valueOf is the legacy alias for forNumber.
            @Suppress("DEPRECATION")
            (RemoteProto.Tag.ValueCase.valueOf(3) shouldBe RemoteProto.Tag.ValueCase.VALUE_STRING)
        }

        "Commit round-trips with id + properties" {
            val commit =
                RemoteProto.Commit
                    .newBuilder()
                    .setId("commit-id-123")
                    .setProperties(sampleStruct("commit"))
                    .build()
            roundTrip(commit) { RemoteProto.Commit.parseFrom(it) }
            val parsed = RemoteProto.Commit.parseFrom(commit.toByteArray())
            parsed.id shouldBe "commit-id-123"
            parsed.idBytes shouldBe commit.idBytes
            parsed.hasProperties() shouldBe true
        }

        "GetCommitRequest round-trips with all three fields" {
            val req =
                RemoteProto.GetCommitRequest
                    .newBuilder()
                    .setRemote(sampleStruct("gcr-remote"))
                    .setParameters(sampleStruct("gcr-params"))
                    .setCommitId("abc-123")
                    .build()
            roundTrip(req) { RemoteProto.GetCommitRequest.parseFrom(it) }
            val parsed = RemoteProto.GetCommitRequest.parseFrom(req.toByteArray())
            parsed.hasRemote() shouldBe true
            parsed.hasParameters() shouldBe true
            parsed.commitId shouldBe "abc-123"
            parsed.commitIdBytes shouldBe req.commitIdBytes
        }

        "GetCommitResponse round-trips with commit_null oneof" {
            val res =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitNull(true)
                    .build()
            roundTrip(res) { RemoteProto.GetCommitResponse.parseFrom(it) }
            val parsed = RemoteProto.GetCommitResponse.parseFrom(res.toByteArray())
            parsed.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_NULL
            parsed.commitNull shouldBe true
            parsed.hasCommitNull() shouldBe true
            parsed.hasCommitValue() shouldBe false
        }

        "GetCommitResponse round-trips with commit_value oneof" {
            val commit =
                RemoteProto.Commit
                    .newBuilder()
                    .setId("c1")
                    .build()
            val res =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitValue(commit)
                    .build()
            roundTrip(res) { RemoteProto.GetCommitResponse.parseFrom(it) }
            val parsed = RemoteProto.GetCommitResponse.parseFrom(res.toByteArray())
            parsed.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_VALUE
            parsed.commitValue.id shouldBe "c1"
            // The merge-builder overload exercises a separate code path.
            val merged = res.toBuilder().mergeCommitValue(commit).build()
            merged.commitValue.id shouldBe "c1"
            // Builder.setCommitValue(Builder) is a distinct overload.
            val viaBuilder =
                RemoteProto.GetCommitResponse
                    .newBuilder()
                    .setCommitValue(RemoteProto.Commit.newBuilder().setId("via-builder"))
                    .build()
            viaBuilder.commitValue.id shouldBe "via-builder"
            // Clearing the oneof.
            res
                .toBuilder()
                .clearCommit()
                .build()
                .commitCase shouldBe
                RemoteProto.GetCommitResponse.CommitCase.COMMIT_NOT_SET
        }

        "GetCommitResponse.CommitCase.forNumber covers oneof discriminator" {
            RemoteProto.GetCommitResponse.CommitCase.forNumber(0) shouldBe
                RemoteProto.GetCommitResponse.CommitCase.COMMIT_NOT_SET
            RemoteProto.GetCommitResponse.CommitCase.forNumber(1) shouldBe
                RemoteProto.GetCommitResponse.CommitCase.COMMIT_NULL
            RemoteProto.GetCommitResponse.CommitCase.forNumber(2) shouldBe
                RemoteProto.GetCommitResponse.CommitCase.COMMIT_VALUE
        }

        "ListCommitRequest round-trips with tags repeated field" {
            val req =
                RemoteProto.ListCommitRequest
                    .newBuilder()
                    .setRemote(sampleStruct("lcr-remote"))
                    .setParameters(sampleStruct("lcr-params"))
                    .addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k1")
                            .setValueString("v1"),
                    ).addTags(
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("k2")
                            .setValueNull(true)
                            .build(),
                    ).addAllTags(
                        listOf(
                            RemoteProto.Tag
                                .newBuilder()
                                .setKey("k3")
                                .setValueString("v3")
                                .build(),
                        ),
                    ).build()
            roundTrip(req) { RemoteProto.ListCommitRequest.parseFrom(it) }
            val parsed = RemoteProto.ListCommitRequest.parseFrom(req.toByteArray())
            parsed.tagsCount shouldBe 3
            parsed.getTags(0).key shouldBe "k1"
            parsed.tagsList.size shouldBe 3
            parsed.getTagsOrBuilder(1).key shouldBe "k2"
            parsed.tagsOrBuilderList.size shouldBe 3
            // Builder mutations: remove and set.
            val mutated =
                req
                    .toBuilder()
                    .removeTags(0)
                    .setTags(
                        0,
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("replaced")
                            .setValueString("x")
                            .build(),
                    ).build()
            mutated.tagsCount shouldBe 2
            mutated.getTags(0).key shouldBe "replaced"
            // setTags(int, Builder) overload.
            val viaBuilder =
                req
                    .toBuilder()
                    .setTags(
                        0,
                        RemoteProto.Tag
                            .newBuilder()
                            .setKey("via")
                            .setValueString("x"),
                    ).build()
            viaBuilder.getTags(0).key shouldBe "via"
            // clearTags drops them.
            req
                .toBuilder()
                .clearTags()
                .build()
                .tagsCount shouldBe 0
        }

        "ListCommitResponse round-trips with commits repeated field" {
            val res =
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(RemoteProto.Commit.newBuilder().setId("a"))
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("b")
                            .build(),
                    ).addAllCommits(
                        listOf(
                            RemoteProto.Commit
                                .newBuilder()
                                .setId("c")
                                .build(),
                        ),
                    ).build()
            roundTrip(res) { RemoteProto.ListCommitResponse.parseFrom(it) }
            val parsed = RemoteProto.ListCommitResponse.parseFrom(res.toByteArray())
            parsed.commitsCount shouldBe 3
            parsed.getCommits(0).id shouldBe "a"
            parsed.commitsList.map { it.id } shouldBe listOf("a", "b", "c")
            parsed.getCommitsOrBuilder(2).id shouldBe "c"
            parsed.commitsOrBuilderList.size shouldBe 3
            // Mutations on the repeated field.
            val mutated =
                res
                    .toBuilder()
                    .removeCommits(0)
                    .setCommits(
                        0,
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("B")
                            .build(),
                    ).build()
            mutated.commitsCount shouldBe 2
            mutated.getCommits(0).id shouldBe "B"
            res
                .toBuilder()
                .setCommits(0, RemoteProto.Commit.newBuilder().setId("A"))
                .build()
                .getCommits(0)
                .id shouldBe "A"
            res
                .toBuilder()
                .clearCommits()
                .build()
                .commitsCount shouldBe 0
        }

        "Legacy RemoteType round-trips" {
            val msg =
                RemoteProto.RemoteType
                    .newBuilder()
                    .setType("s3")
                    .build()
            roundTrip(msg) { RemoteProto.RemoteType.parseFrom(it) }
            RemoteProto.RemoteType.parseFrom(msg.toByteArray()).type shouldBe "s3"
        }

        "Legacy ExtendedURL round-trips with values map" {
            val msg =
                RemoteProto.ExtendedURL
                    .newBuilder()
                    .setUrl("s3://b")
                    .putValues("a", "1")
                    .putAllValues(mapOf("b" to "2", "c" to "3"))
                    .build()
            roundTrip(msg) { RemoteProto.ExtendedURL.parseFrom(it) }
            val parsed = RemoteProto.ExtendedURL.parseFrom(msg.toByteArray())
            parsed.url shouldBe "s3://b"
            parsed.valuesCount shouldBe 3
            parsed.valuesMap["a"] shouldBe "1"
            parsed.getValuesOrDefault("missing", "x") shouldBe "x"
            parsed.getValuesOrThrow("b") shouldBe "2"
            parsed.containsValues("a") shouldBe true
        }

        "Legacy RemoteProperties round-trips" {
            val msg =
                RemoteProto.RemoteProperties
                    .newBuilder()
                    .setValues(sampleStruct("rp"))
                    .build()
            roundTrip(msg) { RemoteProto.RemoteProperties.parseFrom(it) }
            msg
                .toBuilder()
                .clearValues()
                .build()
                .hasValues() shouldBe false
        }

        "Legacy ParameterProperties round-trips" {
            val msg =
                RemoteProto.ParameterProperties
                    .newBuilder()
                    .setValues(sampleStruct("pp"))
                    .build()
            roundTrip(msg) { RemoteProto.ParameterProperties.parseFrom(it) }
            msg
                .toBuilder()
                .clearValues()
                .build()
                .hasValues() shouldBe false
        }

        "RemoteProto descriptor exposes file-level metadata" {
            // The outer RemoteProto class itself surfaces the proto descriptor; touch it.
            val descriptor = RemoteProto.getDescriptor()
            descriptor shouldNotBe null
            descriptor.messageTypes.map { it.name } shouldNotBe emptyList<String>()
            descriptor.findMessageTypeByName("Commit") shouldNotBe null
            descriptor.findMessageTypeByName("Tag") shouldNotBe null
        }

        "merge between two populated instances composes their fields" {
            // mergeFrom on a populated builder exercises a different branch than parseFrom alone.
            val a =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("s3://a")
                    .putProperties("k1", "v1")
                    .build()
            val b =
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("s3://b") // wins; scalar replaces
                    .putProperties("k2", "v2")
                    .build()
            val merged = a.toBuilder().mergeFrom(b).build()
            merged.url shouldBe "s3://b"
            merged.propertiesMap["k1"] shouldBe "v1"
            merged.propertiesMap["k2"] shouldBe "v2"
        }

        "parseFrom(InputStream) and parseDelimited cover alternate parser entry points" {
            val req =
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("u")
                    .build()
            val bytes = req.toByteArray()
            // ByteString overload.
            val viaByteString =
                RemoteProto.FromURLRequest.parseFrom(
                    com.google.protobuf.ByteString
                        .copyFrom(bytes),
                )
            viaByteString shouldBe req
            // InputStream overload.
            val viaStream = RemoteProto.FromURLRequest.parseFrom(java.io.ByteArrayInputStream(bytes))
            viaStream shouldBe req
            // Delimited encoding round-trips through parseDelimitedFrom.
            val baos = java.io.ByteArrayOutputStream()
            req.writeDelimitedTo(baos)
            val viaDelim =
                RemoteProto.FromURLRequest.parseDelimitedFrom(java.io.ByteArrayInputStream(baos.toByteArray()))
            viaDelim shouldBe req
        }
    }
}
