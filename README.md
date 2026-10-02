# homecam

A two-phone home camera system. An old Android phone is the camera *and* the signaling
server; your daily phone is the viewer. No cloud account, no subscription, no third box.

Only devices you have explicitly paired can see a feed, the camera shows how many
devices have access, and every access is logged where you can see it.

## Where things are

| | |
|---|---|
| [`docs/plans/README.md`](docs/plans/README.md) | The roadmap — what is being built next, and where it is up to |
| [`docs/PLAN.md`](docs/PLAN.md) | The design: threat model, wire protocol, milestones |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Where the implementation diverged from the plan, and why |
| [`docs/plans/SECURITY-HARDENING.md`](docs/plans/SECURITY-HARDENING.md) | The security audit's findings and the plan to close them |
| [`docs/REMOTE_ACCESS.md`](docs/REMOTE_ACCESS.md) | Watching from outside the house, over Tailscale |
| [`models/README.md`](models/README.md) | The detector, its licence, and what its class mapping cannot do |
| `core/` | Pure JVM: wire protocol, crypto, handshake, RPC, audit chain, retention. 80 unit tests, no emulator |
| `app/` | Android: signaling, WebRTC, capture, detection, recording, both UIs |

## Building

```sh
source ~/android-tooling/env.sh
./gradlew :core:test          # 80 tests, pure JVM, seconds
./gradlew assembleDebug
./gradlew :app:connectedDebugAndroidTest   # 13 tests: audit tampering + the detector, needs a device
```

One APK carries both roles; which one a phone is gets chosen at first launch.

`assembleDebug` and `assembleRelease` are followed by `verifyNativeAlignment`, which fails
the build if any packaged native library cannot be mapped on a 16 KB page device. Every
`.so` comes from a prebuilt dependency, so that property is decided by which versions are
pinned and is worth checking rather than trusting.

The build targets `arm64-v8a` only, because libwebrtc and TFLite each ship a native
library per ABI and all four is a 119 MB APK against 49. Pass
`-Phomecam.abis=arm64-v8a,x86_64` to add the emulator's, or the full set for a build meant
to run anywhere.

## Using it

1. Install on both phones. On the old one pick **The camera**, on your daily phone pick
   **The viewer**.
2. Camera → **Pair a device**. A code appears for 120 seconds and works once.
3. Viewer → **Add a camera** → scan it.
4. Both phones show a short verification phrase. **They must match** — if they differ,
   something relayed the connection; revoke and pair again.

The camera's home screen says how many devices have access, and `Manage` revokes any of
them immediately, tearing down a live session mid-frame.

## What is verified, and what is not

Everything below has been built and run: `:core`'s 80 unit tests, the on-device audit
tamper tests, and a clean install-and-launch of the debug APK.

**The two-phone path has not been exercised end to end**, because that needs the second
handset in the camera role. Specifically unverified on real hardware: pairing over the
wire, live video, recording and retention, and detection. The protocol logic underneath
each of those is unit-tested; the device integration is not.
