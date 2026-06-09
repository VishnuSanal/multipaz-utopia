# Multipaz Utopia Universe

Sample applications for the fictional "Utopia" universe, built on the
[Multipaz SDK](https://github.com/openwallet-foundation/multipaz). Each organization under
`organizations/` is a self-contained demo (issuer and/or verifier) showcasing a real-world
digital-identity flow.

| Organization | Description |
|---|---|
| `organizations/brewery` | Age-verification demo: a brewery storefront that verifies a customer's age over OpenID4VP. |

## Prerequisites

- JDK 17

## How dependencies are wired

The samples depend on the Multipaz SDK as **published artifacts** — there is no SDK source
checkout involved. The version is pinned in `gradle/libs.versions.toml` as `multipazSdk`:

- A `-SNAPSHOT` value (the default) resolves from the Central snapshots repository, which the
  SDK's "Publish to Maven Central" GitHub Action populates on every push to `main`.
- A release value (e.g. `0.99.0`) resolves from Maven Central.

To build against a different SDK version, change `multipazSdk` in the catalog. To iterate against
unreleased local SDK changes, publish them with
`./gradlew publishToMavenLocal -Psnapshot=true` in the SDK repo.

## Running the brewery demo

```bash
# Serves on port 8010. Use -PbreweryBaseUrl=http://<lan-ip>:8010 when a device cannot reach localhost.
./gradlew :organizations:brewery:backend:run
```

See [`organizations/brewery/README.md`](organizations/brewery/README.md) for details.
