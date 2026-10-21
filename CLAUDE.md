# homecam

This file is published with the repo. Personal or machine-specific notes go in the
gitignored `CLAUDE.local.md`, which Claude Code loads right after it.

Two phones, no cloud: an old Android phone is the camera *and* the signaling server, your
daily phone is the viewer. Only explicitly paired devices can see a feed, the camera shows
how many have access, and every access is logged where you can see it.

## Getting oriented

Read in this order; stop as soon as you have what you need.

| | |
|---|---|
| `docs/PLAN.md` | The design: threat model, wire protocol, storage, media pipeline. M0–M9, all shipped. |
| `docs/DECISIONS.md` | Every place the implementation diverged from that design, and why. Read before changing anything security-shaped. |

`:core` is pure JVM — protocol, crypto, handshake, RPC, audit chain, retention, detection
policy — and must stay free of Android imports so it keeps running as unit tests with no
emulator. `:app` is everything Android.

## Working on it

```sh
source ~/android-tooling/env.sh
./gradlew :core:test                       # pure JVM, seconds
./gradlew :app:connectedDebugAndroidTest   # needs a device
./gradlew assembleDebug
```

Every dependency is hash-locked in `gradle/verification-metadata.xml`, so changing a
version fails the build until the lock is regenerated:

```sh
./gradlew --write-verification-metadata sha256 \
    :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

`assembleDebug` and `assembleRelease` are followed by a native-library alignment check that
fails the build rather than warning; see `docs/DECISIONS.md` D8.

## Things worth knowing before you change something

- **Security is the organising constraint, not a feature.** Identity, pairing, the
  allowlist and the audit log landed before there was a stream worth protecting. Changes
  that touch admission, the handshake transcript, or the audit chain deserve a
  `/security-review` pass.
- **Never run `connectedDebugAndroidTest` against a paired phone**: gradle uninstalls the
  app afterwards and wipes the identity and pairings. Install both APKs with `adb install -r`
  and run `am instrument -w -e class …` instead, and never `SirenTest`, the clip-library
  tests or the whole suite on a phone in use.
- **The UI takes colour, type and spacing from `ui/theme/Theme.kt` and `ui/kit/` and nowhere
  else.** The debug `GalleryActivity` shows every screen with sample data on an emulator.
- **Persistent docs here point at code; they do not describe it.** Plans under
  `docs/plans/` are the opposite and may specify freely, because they are deleted when
  their work ships.
