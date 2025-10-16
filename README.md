# Datadatdat plugin launcher

This project is designed to launch and manage plugins built using the
[Datadatdat Remote SDK for Go](https://github.com/datadatdat/remote-sdk-go). The SDK is based on the 
[HashiCorp go-plugin](https://github.com/hashicorp/go-plugin) framework, which allows arbitrary plugins by
invoking them as subprocesses and communicating over gRPC. This will help in the transition of Datadatdat from Kotlin
from GoLang, even if `Datadatdat-server` remains written in Kotlin for a lengthy period of time.

## Contributing

This project follows the Datadatdat community best practices:

  * [Contributing](https://github.com/datadatdat/.github/blob/master/CONTRIBUTING.md)
  * [Code of Conduct](https://github.com/datadatdat/.github/blob/master/CODE_OF_CONDUCT.md)
  * [Community Support](https://github.com/datadatdat/.github/blob/master/SUPPORT.md)

It is maintained by the [Datadatdat community maintainers](https://github.com/datadatdat/.github/blob/master/MAINTAINERS.md)

For more information on how it works, and how to build and release new versions,
see the [Development Guidelines](DEVELOPING.md).

