/*
 * Copyright The Titan Project Contributors.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.google.protobuf.gradle.*

apply(plugin="com.github.ben-manes.versions")
apply(plugin="com.google.protobuf")

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.github.ben-manes:gradle-versions-plugin:0.27.0")
    }
}

plugins {
    kotlin("jvm") version "1.3.61"
    id("com.github.ben-manes.versions") version("0.27.0")
    id("com.google.protobuf") version("0.8.11")
    `maven-publish`
}

repositories {
    mavenCentral()
    jcenter()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "titan"
        url = uri("https://maven.titan-data.io")
    }
}

val ktlint by configurations.creating
val grpcVersion = "1.26.0"

dependencies {
    compile("com.google.protobuf:protobuf-java:3.11.1")
    compile("io.grpc:grpc-stub:$grpcVersion")
    compile("io.grpc:grpc-netty:$grpcVersion")
    compile("io.grpc:grpc-protobuf:$grpcVersion")
    compile("javax.annotation:javax.annotation-api:1.3.2")
    compile("io.netty:netty-transport-native-epoll:4.1.44.Final:linux-x86_64")
    compile("io.netty:netty-transport-native-kqueue:4.1.44.Final:osx-x86_64")
    compile(kotlin("stdlib"))
    compile("org.slf4j:slf4j-api:1.7.30")
    ktlint("com.pinterest:ktlint:0.36.0")
    testCompile("org.slf4j:slf4j-nop:1.7.30")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.4.2")
    testImplementation("io.mockk:mockk:1.9.3")
}

// Jar configuration
group = "io.titandata"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// Maven publishing configuration
val mavenBucket = when(project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "titan-data-maven"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.titandata"
            artifactId = "plugin-launcher"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "titan"
            url = uri("s3://$mavenBucket")
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}

// Include generated sources in source sets
sourceSets {
    main {
        java {
            srcDir("${buildDir.absolutePath}/generated/source/proto/main/java")
            srcDir("${buildDir.absolutePath}/generated/source/proto/main/grpc")
        }
    }
}

// Treat all warnings as errors
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
        allWarningsAsErrors = true
    }
}

// Configuration for dependencyUpdates task to ignore release candidates
tasks.withType<DependencyUpdatesTask>().configureEach {
    resolutionStrategy {
        componentSelection {
        	all {
        	    val rejected = listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea", "eap").any { qualifier ->
            		candidate.version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
        	    }
        	    if (rejected) {
            		reject("Release candidate")
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
    main = "com.pinterest.ktlint.Main"
    args("src/**/*.kt")
}

tasks.register<JavaExec>("ktlintFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fix Kotlin code style deviations"
    classpath = ktlint
    main = "com.pinterest.ktlint.Main"
    args("-F", "src/**/*.kt")
}

tasks.named("check").get().dependsOn(ktlintTask)

// Build echo plugin binary for tests
val buildEchoPlugin = tasks.register<Exec>("buildEchoPlugin") {
    workingDir = File("${project.rootDir}/src/test/go")
    outputs.dir("${project.buildDir}/go")
    commandLine = listOf("go", "build", "-o", "${project.buildDir}/go", "./echo")
}

tasks.named("test").get().dependsOn(buildEchoPlugin)

// Test configuration
tasks.test {
    useJUnitPlatform()
    systemProperty("pluginDirectory", "${project.buildDir}/go")
}

// GRPC configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.11.1"
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
