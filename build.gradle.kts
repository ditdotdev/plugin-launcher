// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.google.protobuf.gradle.*
import java.time.Duration
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

apply(plugin="com.github.ben-manes.versions")
apply(plugin="com.google.protobuf")

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.54.0")
    }
}

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.github.ben-manes.versions") version("0.54.0")
    id("com.google.protobuf") version("0.9.4")
    jacoco
    id("com.vanniktech.maven.publish") version("0.37.0")
}

repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
}

val ktlint by configurations.creating
val grpcVersion = "1.85.1"

// grpc-netty $grpcVersion brings netty-common 4.1.132.Final transitively. The native-lib
// classifiers must match that version or the JNI loader fails at runtime with NoSuchMethodError
// on io.netty.channel.unix.Socket.sendTo (see arch-review #76 follow-up).
val nettyNativeVersion = "4.1.132.Final"

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("io.netty:netty-transport-native-epoll:$nettyNativeVersion:linux-x86_64")
    implementation("io.netty:netty-transport-native-kqueue:$nettyNativeVersion:osx-x86_64")
    implementation(kotlin("stdlib"))
    implementation("org.slf4j:slf4j-api:2.0.20")
    ktlint("com.pinterest.ktlint:ktlint-cli:1.8.0")
    testImplementation("org.slf4j:slf4j-nop:2.0.20")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
    testImplementation("io.mockk:mockk:1.14.12")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
}

// Jar configuration
group = "dev.dit"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Maven publishing configuration
//
// Releases are published to Maven Central (namespace dev.dit) by the
// com.vanniktech.maven.publish plugin, which also supplies the sources jar,
// javadoc jar and signatures that Central requires. Credentials and the
// signing key come from Gradle properties (ORG_GRADLE_PROJECT_* in CI); see
// DEVELOPING.md.
mavenPublishing {
    // Upload as a deployment that waits for a manual "Publish" click in the
    // Central Portal: anything released to Central is permanent.
    publishToMavenCentral()

    // Sign only when a key is supplied, so `publishToMavenLocal` works on a
    // developer machine or pull request build with no key. A release without
    // signatures cannot slip through: Central rejects unsigned deployments.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // Kotlin sources have no javadoc; Central accepts an empty javadoc jar.
    configureBasedOnAppliedPlugins(
        javadocJar = JavadocJar.Empty(),
        sourcesJar = SourcesJar.Sources(),
    )

    coordinates("dev.dit", "plugin-launcher", version.toString())

    pom {
        name.set("Dit Plugin Launcher")
        description.set("Launches and manages Dit remote provider plugins built with the Dit Remote SDK for Go.")
        inceptionYear.set("2026")
        url.set("https://github.com/ditdotdev/plugin-launcher")
        licenses {
            license {
                name.set("BUSL-1.1")
                url.set("https://github.com/ditdotdev/plugin-launcher/blob/master/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ditdotdev")
                name.set("Dit")
                url.set("https://github.com/ditdotdev")
            }
        }
        scm {
            url.set("https://github.com/ditdotdev/plugin-launcher")
            connection.set("scm:git:https://github.com/ditdotdev/plugin-launcher.git")
            developerConnection.set("scm:git:ssh://git@github.com/ditdotdev/plugin-launcher.git")
        }
    }
}

// Treat all warnings as errors
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// Configuration for dependencyUpdates task to ignore release candidates
tasks.withType<DependencyUpdatesTask>().configureEach {
    resolutionStrategy {
        componentSelection {
        	all { selection: ComponentSelection ->
        	    val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
            		selection.candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
        	    }
        	    if (rejected) {
            		selection.reject("Release candidate")
        	    }
        	}
        }
    }
}

// Enable ktlint checks and formatting
val ktlintTask = tasks.register<JavaExec>("ktlint") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Check Kotlin code style"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("src/**/*.kt")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fix Kotlin code style deviations"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("-F", "src/**/*.kt")
}

tasks.named("check").get().dependsOn(ktlintTask)

// Build echo plugin binary for tests
val buildEchoPlugin = tasks.register<Exec>("buildEchoPlugin") {
    workingDir = project.rootDir
    outputs.dir("${layout.buildDirectory.get().asFile}/go")
    commandLine = listOf("go", "build", "-o", "${layout.buildDirectory.get().asFile}/go", "./src/test/go/echo")
    timeout.set(Duration.ofMinutes(5))
}

tasks.named("test").get().dependsOn(buildEchoPlugin)

// Test configuration
tasks.test {
    useJUnitPlatform()
    systemProperty("pluginDirectory", "${layout.buildDirectory.get().asFile}/go")
    // Add timeout to prevent hanging tests
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

// GRPC configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {
            it.plugins {
                // Apply the "grpc" plugin whose spec is defined above, without options.
                id("grpc")
            }
        }
    }
}
