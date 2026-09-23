# ADR 0034: Bind connectivity updates to the reported default network

## Status

Accepted

## Context

`ValidatedInternetConnectivity` reports whether the Android process default network has both `INTERNET` and `VALIDATED` capabilities. During a handoff between Wi-Fi, cellular, and VPN transports, Android can deliver capability and loss callbacks for different networks in close succession. Re-querying `activeNetwork` for every callback lets a late event for the former network overwrite the state for the newer default network.

## Decision

Track the network reported by the default-network callback. Evaluate validation from the capabilities supplied for that tracked network, or by querying that exact network when it first becomes available. Ignore capability and loss callbacks for any previously replaced network.

The startup snapshot still reads the current process default network, and loss of the currently tracked default reports offline until Android announces a replacement.

## Consequences

* The Settings indicator and cache-only network gates remain stable across transport handoffs.
* A delayed callback cannot resume remote work while the current default is unvalidated or offline.
* The definition of online remains Android-validated internet; local-only network access remains offline.

## Alternatives considered

### Re-query `activeNetwork` for every callback

Rejected because callback timing can make an event from an old network describe a different active network.

### Treat any validated network as online

Rejected because the app's remote relay and HTTP work uses the process default network, not every network visible to the device.
