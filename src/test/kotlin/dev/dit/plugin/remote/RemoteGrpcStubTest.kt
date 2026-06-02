/*
 * Copyright The Dit Project Contributors
 */
package dev.dit.plugin.remote

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.kotlintest.Spec
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import java.util.concurrent.TimeUnit

/**
 * Smoke tests that exercise every generated gRPC stub (Blocking, BlockingV2, Async, Future)
 * against an in-process server backed by a fake AsyncService implementation. This walks
 * the RemoteGrpc.RemoteBlockingStub, RemoteBlockingV2Stub, RemoteStub, RemoteFutureStub,
 * AsyncService, MethodHandlers, ServiceDescriptor, FileDescriptorSupplier, and
 * BaseDescriptorSupplier code paths that the existing mockk-based RemoteClientTest cannot
 * reach.
 */
class RemoteGrpcStubTest : StringSpec() {
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    /**
     * Fake implementation that echoes a deterministic, fully-populated response for every
     * RPC defined by the Remote service. Each response is unique enough to spot a wiring
     * regression where one method's response leaks into another.
     */
    private object FakeRemoteService : RemoteGrpc.AsyncService {
        override fun getType(
            request: RemoteProto.GetTypeRequest,
            responseObserver: StreamObserver<RemoteProto.GetTypeResponse>,
        ) {
            responseObserver.onNext(
                RemoteProto.GetTypeResponse
                    .newBuilder()
                    .setType("fake-type")
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun fromURL(
            request: RemoteProto.FromURLRequest,
            responseObserver: StreamObserver<RemoteProto.FromURLResponse>,
        ) {
            // Echo the URL into a Struct field so the test can verify the request reached us.
            val struct =
                com.google.protobuf.Struct
                    .newBuilder()
                    .putFields(
                        "url",
                        com.google.protobuf.Value
                            .newBuilder()
                            .setStringValue(request.url)
                            .build(),
                    ).build()
            responseObserver.onNext(
                RemoteProto.FromURLResponse
                    .newBuilder()
                    .setRemote(struct)
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun toURL(
            request: RemoteProto.ToURLRequest,
            responseObserver: StreamObserver<RemoteProto.ToURLResponse>,
        ) {
            responseObserver.onNext(
                RemoteProto.ToURLResponse
                    .newBuilder()
                    .setUrl("echoed://to-url")
                    .putProperties("seen-remote", request.hasRemote().toString())
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun getParameters(
            request: RemoteProto.GetParametersRequest,
            responseObserver: StreamObserver<RemoteProto.GetParametersResponse>,
        ) {
            val struct =
                com.google.protobuf.Struct
                    .newBuilder()
                    .putFields(
                        "called",
                        com.google.protobuf.Value
                            .newBuilder()
                            .setBoolValue(true)
                            .build(),
                    ).build()
            responseObserver.onNext(
                RemoteProto.GetParametersResponse
                    .newBuilder()
                    .setParameters(struct)
                    .build(),
            )
            responseObserver.onCompleted()
        }

        override fun validateRemote(
            request: RemoteProto.ValidateRemoteRequest,
            responseObserver: StreamObserver<RemoteProto.ValidateRemoteResponse>,
        ) {
            responseObserver.onNext(RemoteProto.ValidateRemoteResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }

        override fun validateParameters(
            request: RemoteProto.ValidateParametersRequest,
            responseObserver: StreamObserver<RemoteProto.ValidateParametersResponse>,
        ) {
            responseObserver.onNext(RemoteProto.ValidateParametersResponse.getDefaultInstance())
            responseObserver.onCompleted()
        }

        override fun listCommits(
            request: RemoteProto.ListCommitRequest,
            responseObserver: StreamObserver<RemoteProto.ListCommitResponse>,
        ) {
            responseObserver.onNext(
                RemoteProto.ListCommitResponse
                    .newBuilder()
                    .addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("fake-1")
                            .build(),
                    ).addCommits(
                        RemoteProto.Commit
                            .newBuilder()
                            .setId("fake-2")
                            .build(),
                    ).build(),
            )
            responseObserver.onCompleted()
        }

        override fun getCommit(
            request: RemoteProto.GetCommitRequest,
            responseObserver: StreamObserver<RemoteProto.GetCommitResponse>,
        ) {
            // Drive the COMMIT_NULL oneof branch when the caller asks for "null", otherwise
            // drive COMMIT_VALUE. This lets one test cover both oneof transport paths.
            val response =
                if (request.commitId == "null") {
                    RemoteProto.GetCommitResponse
                        .newBuilder()
                        .setCommitNull(true)
                        .build()
                } else {
                    RemoteProto.GetCommitResponse
                        .newBuilder()
                        .setCommitValue(
                            RemoteProto.Commit
                                .newBuilder()
                                .setId(request.commitId)
                                .build(),
                        ).build()
                }
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        }
    }

    /** Concrete subclass to exercise RemoteImplBase.bindService(). */
    private class FakeImplBase : RemoteGrpc.RemoteImplBase() {
        override fun getType(
            request: RemoteProto.GetTypeRequest,
            responseObserver: StreamObserver<RemoteProto.GetTypeResponse>,
        ) {
            FakeRemoteService.getType(request, responseObserver)
        }
    }

    override fun beforeSpec(spec: Spec) {
        val serverName = "plugin-launcher-grpc-test-${System.nanoTime()}"
        server =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(RemoteGrpc.bindService(FakeRemoteService))
                .build()
                .start()
        channel =
            InProcessChannelBuilder
                .forName(serverName)
                .directExecutor()
                .build()
    }

    override fun afterSpec(spec: Spec) {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    init {
        "RemoteBlockingStub exercises every RPC end-to-end" {
            val stub = RemoteGrpc.newBlockingStub(channel)
            stub.getType(RemoteProto.GetTypeRequest.getDefaultInstance()).type shouldBe "fake-type"

            val fromURL =
                stub.fromURL(
                    RemoteProto.FromURLRequest
                        .newBuilder()
                        .setUrl("s3://bucket")
                        .build(),
                )
            fromURL.remote.getFieldsOrThrow("url").stringValue shouldBe "s3://bucket"

            val toURL =
                stub.toURL(
                    RemoteProto.ToURLRequest
                        .newBuilder()
                        .setRemote(
                            com.google.protobuf.Struct
                                .getDefaultInstance(),
                        ).build(),
                )
            toURL.url shouldBe "echoed://to-url"
            toURL.propertiesMap["seen-remote"] shouldBe "true"

            val params =
                stub.getParameters(
                    RemoteProto.GetParametersRequest.newBuilder().build(),
                )
            params.parameters.getFieldsOrThrow("called").boolValue shouldBe true

            stub.validateRemote(
                RemoteProto.ValidateRemoteRequest.getDefaultInstance(),
            ) shouldBe RemoteProto.ValidateRemoteResponse.getDefaultInstance()

            stub.validateParameters(
                RemoteProto.ValidateParametersRequest.getDefaultInstance(),
            ) shouldBe RemoteProto.ValidateParametersResponse.getDefaultInstance()

            val list =
                stub.listCommits(
                    RemoteProto.ListCommitRequest.newBuilder().build(),
                )
            list.commitsCount shouldBe 2
            list.getCommits(0).id shouldBe "fake-1"

            val withValue =
                stub.getCommit(
                    RemoteProto.GetCommitRequest
                        .newBuilder()
                        .setCommitId("c1")
                        .build(),
                )
            withValue.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_VALUE
            withValue.commitValue.id shouldBe "c1"

            val withNull =
                stub.getCommit(
                    RemoteProto.GetCommitRequest
                        .newBuilder()
                        .setCommitId("null")
                        .build(),
                )
            withNull.commitCase shouldBe RemoteProto.GetCommitResponse.CommitCase.COMMIT_NULL
        }

        "RemoteBlockingV2Stub exercises every RPC end-to-end" {
            val stub = RemoteGrpc.newBlockingV2Stub(channel)
            stub.getType(RemoteProto.GetTypeRequest.getDefaultInstance()).type shouldBe "fake-type"
            stub
                .fromURL(
                    RemoteProto.FromURLRequest
                        .newBuilder()
                        .setUrl("u")
                        .build(),
                ).remote
                .getFieldsOrThrow("url")
                .stringValue shouldBe "u"
            stub.toURL(RemoteProto.ToURLRequest.getDefaultInstance()).url shouldBe "echoed://to-url"
            stub
                .getParameters(RemoteProto.GetParametersRequest.getDefaultInstance())
                .parameters
                .getFieldsOrThrow("called")
                .boolValue shouldBe true
            stub.validateRemote(RemoteProto.ValidateRemoteRequest.getDefaultInstance()) shouldNotBe null
            stub.validateParameters(RemoteProto.ValidateParametersRequest.getDefaultInstance()) shouldNotBe null
            stub.listCommits(RemoteProto.ListCommitRequest.getDefaultInstance()).commitsCount shouldBe 2
            stub
                .getCommit(
                    RemoteProto.GetCommitRequest
                        .newBuilder()
                        .setCommitId("x")
                        .build(),
                ).commitValue.id shouldBe "x"
        }

        "RemoteFutureStub exercises every RPC end-to-end" {
            val stub = RemoteGrpc.newFutureStub(channel)
            stub.getType(RemoteProto.GetTypeRequest.getDefaultInstance()).get(5, TimeUnit.SECONDS).type shouldBe "fake-type"
            stub
                .fromURL(
                    RemoteProto.FromURLRequest
                        .newBuilder()
                        .setUrl("f")
                        .build(),
                ).get(5, TimeUnit.SECONDS)
                .remote
                .getFieldsOrThrow("url")
                .stringValue shouldBe "f"
            stub.toURL(RemoteProto.ToURLRequest.getDefaultInstance()).get(5, TimeUnit.SECONDS).url shouldBe "echoed://to-url"
            stub
                .getParameters(RemoteProto.GetParametersRequest.getDefaultInstance())
                .get(5, TimeUnit.SECONDS)
                .parameters
                .getFieldsOrThrow("called")
                .boolValue shouldBe true
            stub.validateRemote(RemoteProto.ValidateRemoteRequest.getDefaultInstance()).get(5, TimeUnit.SECONDS) shouldNotBe null
            stub
                .validateParameters(RemoteProto.ValidateParametersRequest.getDefaultInstance())
                .get(5, TimeUnit.SECONDS) shouldNotBe null
            stub
                .listCommits(RemoteProto.ListCommitRequest.getDefaultInstance())
                .get(5, TimeUnit.SECONDS)
                .commitsCount shouldBe 2
            stub
                .getCommit(
                    RemoteProto.GetCommitRequest
                        .newBuilder()
                        .setCommitId("fut")
                        .build(),
                ).get(5, TimeUnit.SECONDS)
                .commitValue.id shouldBe "fut"
        }

        "RemoteStub (async) exercises every RPC end-to-end" {
            val stub = RemoteGrpc.newStub(channel)
            // Collect each method's response synchronously to keep the test deterministic.
            val getTypeResult = java.util.concurrent.CompletableFuture<RemoteProto.GetTypeResponse>()
            stub.getType(
                RemoteProto.GetTypeRequest.getDefaultInstance(),
                observerFor(getTypeResult),
            )
            getTypeResult.get(5, TimeUnit.SECONDS).type shouldBe "fake-type"

            val fromURLResult = java.util.concurrent.CompletableFuture<RemoteProto.FromURLResponse>()
            stub.fromURL(
                RemoteProto.FromURLRequest
                    .newBuilder()
                    .setUrl("async-url")
                    .build(),
                observerFor(fromURLResult),
            )
            fromURLResult
                .get(5, TimeUnit.SECONDS)
                .remote
                .getFieldsOrThrow("url")
                .stringValue shouldBe "async-url"

            val toURLResult = java.util.concurrent.CompletableFuture<RemoteProto.ToURLResponse>()
            stub.toURL(RemoteProto.ToURLRequest.getDefaultInstance(), observerFor(toURLResult))
            toURLResult.get(5, TimeUnit.SECONDS).url shouldBe "echoed://to-url"

            val getParametersResult = java.util.concurrent.CompletableFuture<RemoteProto.GetParametersResponse>()
            stub.getParameters(
                RemoteProto.GetParametersRequest.getDefaultInstance(),
                observerFor(getParametersResult),
            )
            getParametersResult
                .get(5, TimeUnit.SECONDS)
                .parameters
                .getFieldsOrThrow("called")
                .boolValue shouldBe true

            val validateRemoteResult = java.util.concurrent.CompletableFuture<RemoteProto.ValidateRemoteResponse>()
            stub.validateRemote(
                RemoteProto.ValidateRemoteRequest.getDefaultInstance(),
                observerFor(validateRemoteResult),
            )
            validateRemoteResult.get(5, TimeUnit.SECONDS) shouldNotBe null

            val validateParametersResult = java.util.concurrent.CompletableFuture<RemoteProto.ValidateParametersResponse>()
            stub.validateParameters(
                RemoteProto.ValidateParametersRequest.getDefaultInstance(),
                observerFor(validateParametersResult),
            )
            validateParametersResult.get(5, TimeUnit.SECONDS) shouldNotBe null

            val listResult = java.util.concurrent.CompletableFuture<RemoteProto.ListCommitResponse>()
            stub.listCommits(RemoteProto.ListCommitRequest.getDefaultInstance(), observerFor(listResult))
            listResult.get(5, TimeUnit.SECONDS).commitsCount shouldBe 2

            val getCommitResult = java.util.concurrent.CompletableFuture<RemoteProto.GetCommitResponse>()
            stub.getCommit(
                RemoteProto.GetCommitRequest
                    .newBuilder()
                    .setCommitId("ac")
                    .build(),
                observerFor(getCommitResult),
            )
            getCommitResult.get(5, TimeUnit.SECONDS).commitValue.id shouldBe "ac"
        }

        "default AsyncService implementations surface UNIMPLEMENTED on every RPC" {
            // The AsyncService interface declares default implementations for every method
            // that delegate to ServerCalls.asyncUnimplementedUnaryCall. Binding an empty
            // AsyncService that overrides nothing exercises every default body, which
            // a server-side test with overrides would otherwise miss.
            val emptyService = object : RemoteGrpc.AsyncService {}
            val name = "plugin-launcher-empty-${System.nanoTime()}"
            val emptyServer =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(RemoteGrpc.bindService(emptyService))
                    .build()
                    .start()
            val emptyChannel =
                InProcessChannelBuilder
                    .forName(name)
                    .directExecutor()
                    .build()
            try {
                val stub = RemoteGrpc.newBlockingStub(emptyChannel)
                val calls: List<() -> Any> =
                    listOf(
                        { stub.getType(RemoteProto.GetTypeRequest.getDefaultInstance()) },
                        {
                            stub.fromURL(
                                RemoteProto.FromURLRequest
                                    .newBuilder()
                                    .setUrl("u")
                                    .build(),
                            )
                        },
                        { stub.toURL(RemoteProto.ToURLRequest.getDefaultInstance()) },
                        { stub.getParameters(RemoteProto.GetParametersRequest.getDefaultInstance()) },
                        { stub.validateRemote(RemoteProto.ValidateRemoteRequest.getDefaultInstance()) },
                        { stub.validateParameters(RemoteProto.ValidateParametersRequest.getDefaultInstance()) },
                        { stub.listCommits(RemoteProto.ListCommitRequest.getDefaultInstance()) },
                        { stub.getCommit(RemoteProto.GetCommitRequest.getDefaultInstance()) },
                    )
                for (call in calls) {
                    shouldThrow<io.grpc.StatusRuntimeException> { call() }
                }
            } finally {
                emptyChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
                emptyServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        "RemoteImplBase.bindService delegates AsyncService default impls" {
            // bindService is the BindableService hook used by gRPC servers to register a
            // concrete implementation. The default AsyncService overrides drive the
            // asyncUnimplementedUnaryCall fallback when a method isn't overridden, so RPCs
            // for non-overridden methods produce StatusRuntimeException(UNIMPLEMENTED).
            val implBaseServerName = "plugin-launcher-implbase-${System.nanoTime()}"
            val implBaseServer =
                InProcessServerBuilder
                    .forName(implBaseServerName)
                    .directExecutor()
                    .addService(FakeImplBase())
                    .build()
                    .start()
            val implBaseChannel =
                InProcessChannelBuilder
                    .forName(implBaseServerName)
                    .directExecutor()
                    .build()
            try {
                val stub = RemoteGrpc.newBlockingStub(implBaseChannel)
                stub.getType(RemoteProto.GetTypeRequest.getDefaultInstance()).type shouldBe "fake-type"
                // Methods not overridden on FakeImplBase fall through to AsyncService defaults,
                // which surface as UNIMPLEMENTED at the call site.
                shouldThrow<io.grpc.StatusRuntimeException> {
                    stub.fromURL(
                        RemoteProto.FromURLRequest
                            .newBuilder()
                            .setUrl("x")
                            .build(),
                    )
                }
            } finally {
                implBaseChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
                implBaseServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        }

        "getServiceDescriptor exposes all eight method descriptors" {
            val sd = RemoteGrpc.getServiceDescriptor()
            sd.name shouldBe "remote.Remote"
            sd.methods.map { it.fullMethodName }.toSet() shouldBe
                setOf(
                    "remote.Remote/GetType",
                    "remote.Remote/FromURL",
                    "remote.Remote/ToURL",
                    "remote.Remote/GetParameters",
                    "remote.Remote/ValidateRemote",
                    "remote.Remote/ValidateParameters",
                    "remote.Remote/ListCommits",
                    "remote.Remote/GetCommit",
                )
            // Schema descriptor is wired up by the FileDescriptorSupplier.
            sd.schemaDescriptor shouldNotBe null
            // Direct per-method getters cover the cached MethodDescriptor accessors.
            RemoteGrpc.getGetTypeMethod() shouldNotBe null
            RemoteGrpc.getFromURLMethod() shouldNotBe null
            RemoteGrpc.getToURLMethod() shouldNotBe null
            RemoteGrpc.getGetParametersMethod() shouldNotBe null
            RemoteGrpc.getValidateRemoteMethod() shouldNotBe null
            RemoteGrpc.getValidateParametersMethod() shouldNotBe null
            RemoteGrpc.getListCommitsMethod() shouldNotBe null
            RemoteGrpc.getGetCommitMethod() shouldNotBe null
        }
    }

    /** Builds a StreamObserver that completes a CompletableFuture when the unary call finishes. */
    private fun <T> observerFor(future: java.util.concurrent.CompletableFuture<T>): StreamObserver<T> =
        object : StreamObserver<T> {
            private var value: T? = null

            override fun onNext(v: T) {
                value = v
            }

            override fun onError(t: Throwable) {
                future.completeExceptionally(t)
            }

            override fun onCompleted() {
                future.complete(value)
            }
        }
}
