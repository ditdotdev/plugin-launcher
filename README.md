# Dit plugin launcher

This project is designed to launch and manage plugins built using the
[Dit Remote SDK for Go](https://github.com/ditdotdev/remote-sdk-go). The SDK is based on the 
[HashiCorp go-plugin](https://github.com/hashicorp/go-plugin) framework, which allows arbitrary plugins by
invoking them as subprocesses and communicating over gRPC. This will help in the transition of Dit from Kotlin
from GoLang, even if `Dit-server` remains written in Kotlin for a lengthy period of time.

## Contributing

This project follows the Dit community best practices:

  * [Contributing](https://github.com/ditdotdev/.github/blob/master/CONTRIBUTING.md)
  * [Code of Conduct](https://github.com/ditdotdev/.github/blob/master/CODE_OF_CONDUCT.md)
  * [Community Support](https://github.com/ditdotdev/.github/blob/master/SUPPORT.md)

It is maintained by the [Dit community maintainers](https://github.com/ditdotdev/.github/blob/master/MAINTAINERS.md)

For more information on how it works, and how to build and release new versions,
see the [Development Guidelines](DEVELOPING.md).


## License

This project is licensed under the Business Source License 1.1 (BUSL-1.1).
On the Change Date (four years from the publication of each version), the
license for that version converts to the Mozilla Public License 2.0
(MPL-2.0). See [LICENSE](LICENSE) for the full terms.

## Third-Party Code

The `engine/src/main/kotlin/com/delphix/sdk/` and
`engine/src/test/kotlin/com/delphix/sdk/` trees are a vendored Delphix SDK,
Copyright (c) 2019 by Delphix, and are licensed under the Apache License 2.0
(see [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt) and the per-file
headers). The BUSL-1.1 terms in [LICENSE](LICENSE) apply to the rest of this
repository.
