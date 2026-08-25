# Decisions that deviate from, or resolve open points in, `PLAN.md`

## D1 — Identity keys are EC P-256, not Ed25519

`PLAN.md` §2.2 specifies Ed25519 identity keys in the Android Keystore. Implemented as
**secp256r1 (P-256) with ECDSA-SHA256** instead.

The plan's actual requirement is that the private key is generated on-device, is
hardware-backed where available, and never leaves. Android Keystore only supports Ed25519
from **API 33**, and only on some vendor implementations; below that an Ed25519 key would
have to live in app storage wrapped by a Keystore AES key — software, exportable in
principle, and weaker than what the plan asks for. P-256 has been Keystore-native since
API 23 and StrongBox-eligible since API 28, so on `minSdk 26` every device gets the
property the plan wanted.

It also makes §2.2's second clause work: the WebSocket's self-signed certificate is
issued *directly from the identity key*, so "bound to its identity key" is an identity
rather than a derivation. Android's TLS stack (Conscrypt) does not accept Ed25519
certificates, so with Ed25519 the cert key and the identity key would have to be
different keys with a signed statement linking them.

Nothing above the `IdentityKey` / `IdentitySigner` interfaces knows the algorithm.

## D2 — Verification phrase is a character code, not words

§2.3 calls for "a short verification phrase" shown on both devices during pairing. It is
rendered as **8 characters in two groups** (`MK4T-9QPX`) over the same ambiguity-free
alphabet as the roster fingerprints, rather than a wordlist.

Both devices display it at the same moment and the user compares them side by side; that
is a matching task, not a recall task, so the memorability a wordlist buys is not used,
and 256 words of embedded data is a maintenance liability. 40 bits over a 120-second
window is far beyond what a relay attack can search.

## D3 — Handshake transcripts are length-prefixed and domain-separated

Every signature covers a canonical transcript in which each field is length-prefixed, so
no two different field splits can produce the same bytes. Camera and viewer signatures
cover the *same* transcript under *different* role labels, so a signature from one role
can never be replayed as the other's.

## D4 — The invite pins the identity key, not the certificate

`PLAN.md` §2.3 puts four things in the QR: the camera's identity public key, its
certificate fingerprint, host/port, and the one-time token. The certificate fingerprint
is gone.

D1 made the TLS certificate an artifact of the identity key — the Keystore issues it from
that key and nothing else. Pinning both is therefore pinning the same secret twice, and
two pins for one key is a state that can disagree with itself for no benefit. The viewer
pins the SubjectPublicKeyInfo and its trust manager checks the served certificate's key
against it, which additionally survives the camera reissuing its certificate.

The transcript still binds a certificate hash, and that binding is not redundant: it is
the SHA-256 of the certificate **actually presented on this connection**, which the viewer
reads off the live TLS session rather than from the QR. That is what makes the signature
say something about *which* channel was authenticated (§2.5's argument, one layer down).
It is why `ViewerHandshake` is constructed in `onOpen` and not before — the value does not
exist until TLS has completed.

Dropping the field also takes the invite from ~280 to ~245 characters, back inside a
version-10 QR.

## D5 — RPC rides the signaling channel, and the DataChannel when one exists

`PLAN.md` §4.2 puts the RPC envelope on the WebRTC DataChannel. Here it goes over
whichever of the two authenticated transports is available, preferring the DataChannel
when a media session is up.

Alfred puts RPC on the DataChannel because its signaling is a cloud relay it does not want
to route control traffic through. Ours is a direct, pinned, mutually authenticated
WebSocket to the camera itself — so the reason does not carry over, and insisting on the
DataChannel would mean **spinning up a peer connection and a media session to read the
battery level or the access roster**. Browsing clips or checking who has access should not
cost a camera wakeup and an H.264 encode.

The DataChannel is still preferred whenever it is open, because when a media session is
running it is the path that survives NAT traversal without the WebSocket needing its own.
Both feed one `RpcPeer` with one registry and one dispatcher; which socket the bytes leave
by is a transport detail, not a second protocol.

## D6 — One encoder continuously; a second one only while somebody is watching

`PLAN.md` §6 makes "one H.264 encode feeds both the live session and the recorder" the
key battery and thermal trick. Implemented as: **the continuous path has exactly one
encoder**, and a second encoder exists only for the minutes a viewer is actually
connected.

With nobody watching — which is the state the camera is in essentially always — there is
no WebRTC encode at all, only the recorder's MediaCodec. That is where the trick pays,
and it is paid in full.

Teeing WebRTC's encoder into the muxer during a live session was the alternative, and it
is worse for a reason that has nothing to do with effort: **WebRTC's bitrate is driven by
congestion control**. Feeding its output to the file would mean a recording that gets
worse because the Wi-Fi was busy — degrading the one artifact that has to be good later,
in exchange for saving encoder time during the rarest state. The recording keeps its own
encoder and its own bitrate.

Both encoders read the same masked frames from the same capture, so the privacy mask and
the camera controls apply identically to each.

## D7 — The audit log keeps a tail marker outside the database

§2.7's hash chain detects an edited or deleted row, and the instrumented test written to
prove it found the gap: **deleting the last row leaves a chain that verifies perfectly.**
A chain proves nothing about entries that were never there.

That is not a corner case. The last row is `CONNECTION_REJECTED` for somebody who was just
refused, or `SESSION_OPENED` for somebody who just looked — truncation is the natural
attack, not an exotic one.

So `AuditLog` also writes `{seq, chainHash}` to `audit-head.bin` in app-private storage
after every append, and `verify()` reports a log shorter than its own marker as tampering
rather than as a short history. It is defence in depth rather than a proof — somebody who
can rewrite the database can rewrite the file next to it — but it turns a silent,
single-statement truncation into one that has to know about and match a second artifact.

Covered by `AuditTamperTest` on device, which deletes from the middle, deletes the tail,
and empties the table.


## D8 — LiteRT instead of the TFLite Task Library, for 16 KB page compatibility

Android 15 allows a 16 KB memory page, and a native library whose ELF `LOAD` segments are
aligned to 4 KB cannot be mapped on such a device. Of the six native libraries this APK
packages, exactly one was 4 KB aligned: `libtask_vision_jni.so`, from
`org.tensorflow:tensorflow-lite-task-vision:0.4.4`. The other five — including libwebrtc
and ML Kit — were already at `0x4000`.

`0.4.4` is that library's **last** release; the Task Library was deprecated before the
16 KB requirement existed, so there is no version to upgrade to. Two replacements were
measured rather than assumed:

| Candidate | Alignment | Why not / why |
|---|---|---|
| MediaPipe `tasks-vision` | 16 KB | Pulls `datatransport` + `firebase-encoders`, a telemetry stack. §2.9 says no analytics — not "disabled", absent. |
| `com.google.ai.edge.litert:litert:1.4.0` | 16 KB | Depends on nothing but its own API. **Chosen.** |
| `litert:2.0.2` | — | Pulls Play services and asset delivery. |

Re-checked at `litert:2.2.0` (August 2026): it still resolves `litert-api`, which still
depends on `com.google.android.play:ai-delivery`. The 1.4.0 pin stands, and this note
exists so the next person does not resolve those POMs a third time.

LiteRT is the interpreter alone, so the two things the Task Library was doing now live in
our code: the label map ships beside the model as an asset, and the four output tensors are
read directly. That is less of a change than it sounds, because the model ends in a
`TFLite_Detection_PostProcess` op — decoding and non-maximum suppression already happened
inside the graph, so there is no postprocessing to get wrong. The output *order* is a
contract of that op rather than of this file, so `ObjectDetector.create` verifies the
tensor shapes and returns null instead of producing confident nonsense.

**The property is now checked at build time.** Every `.so` here comes from a prebuilt
dependency, so 16 KB compatibility is decided entirely by which versions are pinned —
exactly the thing that regresses silently on a routine bump. `verifyDebugNativeAlignment`
and `verifyReleaseNativeAlignment` parse the ELF program headers of every packaged library
and fail the build below 16 KB, each finalizing its own `assemble` task and taking that
variant's APK directory as a declared input.

The check is only worth having if it has been watched to fail, so it is re-tested against a
deliberately 4 KB-aligned APK whenever it changes.

## D9 — The toolchain tracks the current stable line, not `PLAN.md` §8

§8 pins a December 2024 toolchain and says checksums are locked in
`gradle/verification-metadata.xml`. Both statements have been replaced rather than
adjusted: the versions are now the current stable line — AGP 9, Gradle 9, Kotlin 2.4,
compileSdk 37 — and the lock file exists.

The bump was not optional. Current AndroidX refuses to be consumed below AGP 9.1 and
compileSdk 37, so the alternative was pinning AndroidX backwards to keep AGP 8, which
inverts the intent: the project would be old because it was old.

Two consequences worth knowing before touching the build:

- **AGP 9 carries Kotlin itself.** Applying `org.jetbrains.kotlin.android` to `:app` is
  now an error, and `kotlinOptions` is `compilerOptions`. `:core` still applies
  `kotlin-jvm`; it is not an Android module.
- **compileSdk and targetSdk are deliberately different.** Compile against the newest
  APIs, but opt into Android 16 runtime behaviour, which is what the viewer phone runs.
  Android 17's behaviour changes have not been reviewed here, and raising targetSdk is
  the act that opts into them.

The version that matters for reasons other than currency is **libwebrtc**, which was
twenty months behind. It is a C++ stack parsing untrusted RTP, SRTP and STUN from anyone
who can reach the port, which is T3 and T5 — so it is on the short list of dependencies
where being behind is a vulnerability rather than an inconvenience, along with
Java-WebSocket. Bump those two on their own schedule, not only when something else forces
it.

## D10 — Clips are fragmented MP4 written by media3, not `MediaMuxer`

§5 says "fragmented MP4" and the recorder used `MediaMuxer`, which writes the `moov`
index only on `stop()`. A camera phone that lost power or was killed mid-clip therefore
produced a file with no index and no recoverable video at all — not a truncated clip,
nothing. That is the ordinary way this device dies, and it gets more likely the longer a
clip runs, which phase 4 is about to make routine.

`androidx.media3:media3-muxer` writes the index up front and completes a fragment every
keyframe interval, so the same interruption costs seconds. Fragment duration is tied to
the keyframe interval because a fragment that does not begin on an IDR is not
independently decodable, so no other boundary is worth anything.

It also removed the API 29 floor on timed metadata: media3 writes the `mett` sample entry
itself, so the detection track — and with it the self-describing clip §6 wants — now
exists on every device the app supports, not only Android 10 and up.

## D11 — The recorder picks HEVC; the live path stays H.264

§6 assumes H.264 throughout because Alfred needed it: browsers and a decade-wide device
fleet. Neither applies to two phones that both encode and decode HEVC in hardware.

The recorder now probes for a hardware HEVC encoder and uses it, at 60% of the H.264
bitrate for the same quality — which is 40% more history inside the same `maxSizeGb`
ceiling, on a device where storage is what bounds how far back you can look. Only a
hardware encoder counts: a software one would spend more battery and heat than the
smaller file saves, which is the entire justification, so the probe rejects it and falls
back. That also keeps an older camera phone working, since the choice is per device
rather than per build.

The live path is untouched. It has its own encoder (D6) and its bitrate is set by
congestion control rather than by us, so there is nothing to win there and interop to
lose.

## D12 — Alerts run under `specialUse`, and there is a boot receiver

Two ways the app quietly stopped working on a current Android release.

**The alert listener was a `dataSync` foreground service.** For apps targeting Android 15
that type is capped at six hours in any twenty-four. The listener holds one long-lived
connection to the user's own push endpoint for as long as alerts are on, so alerts would
have stopped part-way through every day, silently — the failure §2.8 exists to prevent.
`specialUse` is the type for a connection the app must keep because there is no
third-party push service to keep it for us, and it carries no timeout.

**§9.3's boot receiver did not exist**, though the permission was declared —
`CameraService` was only ever started from a composable, so after a power cut the camera
stayed down until somebody opened the app.

The receiver now restarts whichever role the install is, and survives an app update too.
Newer Android versions refuse some foreground-service starts from `BOOT_COMPLETED`, and
when the system refuses there is nothing the receiver can do about it — so it posts an
ongoing "not running, tap to resume" notification instead. Stopping is survivable;
stopping silently is not, which is the same argument §2.7 makes about the audit log.
