# Project Development

For general information about contributing changes, see the
[Contributor Guidelines](https://github.com/datadatdat/.github/blob/master/CONTRIBUTING.md).

## How it Works

The launcher is based on HashiCorp's go-plugin infrastructure, which itself is based on gRPC. Plugins are launched
as sub-processes with a predefined handshake. The caller must provide, in environment variables, the right
handshake to indicate that we know what it is we're trying to invoke. The program will then output, in its first
line, the information required to connect to the sub-process over gRPC. We have a pretty simple use case, and
one that is likely transient (until we can rewrite `Datadatdat-server` in golang), so we have very simple sub-process
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

The SDK jar is published when a tag is created in the master branch.
