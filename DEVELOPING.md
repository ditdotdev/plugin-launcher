# Project Development

For general information about contributing changes, see the
[Contributor Guidelines](https://github.com/ditdotdev/.github/blob/master/CONTRIBUTING.md).

## How it Works

The launcher is based on HashiCorp's go-plugin infrastructure, which itself is based on gRPC. Plugins are launched
as sub-processes with a predefined handshake. The caller must provide, in environment variables, the right
handshake to indicate that we know what it is we're trying to invoke. The program will then output, in its first
line, the information required to connect to the sub-process over gRPC. We have a pretty simple use case, and
one that is likely transient (until we can rewrite `Dit-server` in golang), so we have very simple sub-process
management and version checking.

On top of this basic infrastructure, we then provider the Remote interface supported by remote plugins. This
includes a java native interface, the gRPC-generated wrappers, and the glue between the two. This allows
any callers to instantiate `RemotePluginFactory` and call `load()` to get a handle to a remote go plugin that
can then be used in native java code.

## Building

Run `gradle build`.

## Testing

Tests are run automatically as part of `gradle build`, but can also be
explicitly run via `gradle test`.

## Releasing

Creating a tag on the master branch runs the release workflow, which:

1. Publishes the jar to the Dit maven bucket (`https://maven.dit.dev`), kept
   while the ecosystem migrates to Maven Central.
2. Uploads the signed jar, sources jar, javadoc jar and POM to
   [Maven Central](https://central.sonatype.com/) as `dev.dit:plugin-launcher`.

The Maven Central upload is not released automatically. Open
[Deployments](https://central.sonatype.com/publishing/deployments) in the
Central Portal, check the validated deployment, and click **Publish**. A
published version is permanent and cannot be changed or removed.

The workflow reads four repository secrets: `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD` (a Central Portal user token), and `SIGNING_KEY` and
`SIGNING_PASSWORD` (the ASCII-armored GPG private key and its passphrase).

### Checking the publication locally

`scripts/verify-publication.sh plugin-launcher` publishes to a throwaway
local repository and checks everything Maven Central requires: the sources and
javadoc jars and the POM name, description, URL, license, developer and SCM
entries. It runs on every pull request. Signatures are checked as well when
`ORG_GRADLE_PROJECT_signingInMemoryKey` is set.
